// Клиент API ИИ-чата Atlorium — суммаризация текста нейросетью через HTTP.
//
// Запуск (работает сразу, без регистрации — на демо-ключе):
//
//	go run .
//	go run . "свой текст для суммаризации"
//
// Боевой ключ: получить на https://atlorium.com и положить в переменную окружения
// ATLORIUM_API_KEY. Код при этом не меняется.
package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"strconv"
	"time"
)

// SandboxKey — публичный демо-ключ. С ним /send возвращает МОК: реальная ИИ-модель
// не вызывается, токены не тратятся, а в поле reply лежит заглушка, которая честно
// об этом сообщает. Механику (выбор модели, сессии, учёт токенов) на нём отладить
// можно, саму суммаризацию — нет. Для настоящих ответов модели нужен боевой ключ.
const SandboxKey = "ak_sandbox_demo_mockdata_v1"

// MaxRetries — повтор после 429 ровно один раз. Лимит у ИИ-чата самый жёсткий
// в Atlorium, поэтому повторять запросы подряд бессмысленно.
const MaxRetries = 1

// MaxRetryDelay — потолок ожидания. Исчерпав ЧАСОВОЙ лимит, сервер честно просит
// подождать 40+ минут. Спать столько нельзя — процесс (и CI-джоб) просто зависнет.
// Дольше потолка не ждём, а честно сообщаем «квота исчерпана» и выходим.
const MaxRetryDelay = 120 * time.Second

var (
	apiKey  = envOr("ATLORIUM_API_KEY", SandboxKey)
	baseURL = envOr("ATLORIUM_BASE_URL", "https://atlorium.com")
	client  = &http.Client{Timeout: 60 * time.Second}
)

func envOr(key, fallback string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return fallback
}

// AiModelInfo — модель из GET /api/AiChat/models.
type AiModelInfo struct {
	ID                  string `json:"id"`
	Name                string `json:"name"`
	Description         string `json:"description"`
	ContextWindowTokens int    `json:"contextWindowTokens"`
	MaxInputTokens      int    `json:"maxInputTokens"`
	MaxOutputTokens     int    `json:"maxOutputTokens"`
	MaxTokens           int    `json:"maxTokens"`
	// IsAvailable — ключевое поле: сервер может отключить модель,
	// и тогда /send с её id не пройдёт.
	IsAvailable bool `json:"isAvailable"`
	// IsLocal — true, если модель крутится на сервере Atlorium
	// и переписка не уходит наружу.
	IsLocal bool `json:"isLocal"`
	// Ценовые поля необязательные: если сервер их не прислал, encoding/json оставит
	// нули — и это ровно тот безопасный дефолт, который нужен («токены не тарифицируются»).
	//
	// InputPricePer1kTokens — цена входных токенов модели, кредитов за 1000.
	// 0 означает, что токены этой модели не тарифицируются.
	InputPricePer1kTokens float64 `json:"inputPricePer1kTokens"`
	// OutputPricePer1kTokens — цена выходных токенов модели, кредитов за 1000.
	OutputPricePer1kTokens float64 `json:"outputPricePer1kTokens"`
	// FreeQuotaEligible — покрывает ли запросы к этой модели дневная квота тарифа.
	FreeQuotaEligible bool `json:"freeQuotaEligible"`
}

// AiChatRequest — тело POST /api/AiChat/send.
type AiChatRequest struct {
	Message   string  `json:"message"`
	SessionID *string `json:"sessionId"`
	Model     *string `json:"model"`
}

// AiChatResponse — ответ POST /api/AiChat/send.
type AiChatResponse struct {
	SessionID        string `json:"sessionId"`
	Reply            string `json:"reply"`
	Model            string `json:"model"`
	PromptTokens     int    `json:"promptTokens"`
	CompletionTokens int    `json:"completionTokens"`
	TotalTokens      int    `json:"totalTokens"`
	CreatedAt        string `json:"createdAt"`
}

// AiChatSession — ответ GET /api/AiChat/session/{id}.
type AiChatSession struct {
	SessionID      string `json:"sessionId"`
	MessageCount   int    `json:"messageCount"`
	CreatedAt      string `json:"createdAt"`
	LastActivityAt string `json:"lastActivityAt"`
}

// APIError раскладывает HTTP-код в человекочитаемую причину.
type APIError struct {
	Status int
	Body   string
}

func (e *APIError) Error() string {
	reasons := map[int]string{
		400: "сообщение пустое, длиннее лимита выбранной модели или указана неизвестная модель",
		401: "API-ключ отсутствует, просрочен или недействителен",
		402: "недостаточно кредитов: перед вызовом модели удерживается максимально возможная " +
			"стоимость запроса — пополните на https://atlorium.com",
		404: "сессия не найдена или истекла",
		429: "превышен лимит запросов — повторите позже",
		500: "ошибка ИИ-сервиса при обработке запроса (в том числе исчерпан лимит сообщений в сессии)",
		503: "выбранная модель временно недоступна — возьмите другую из GET /api/AiChat/models " +
			"(за сбой на своей стороне мы не списываем деньги)",
	}
	reason, ok := reasons[e.Status]
	if !ok {
		reason = "неизвестная ошибка"
	}
	return fmt.Sprintf("HTTP %d: %s. Ответ сервера: %s", e.Status, reason, e.Body)
}

// do выполняет запрос и возвращает тело вместе с HTTP-кодом: часть вызывающих
// (сессия) обрабатывает 404 как штатный случай, а не как ошибку.
func do(method, path string, body []byte) (int, []byte, http.Header, error) {
	var reader io.Reader
	if body != nil {
		reader = bytes.NewReader(body)
	}

	request, err := http.NewRequest(method, baseURL+path, reader)
	if err != nil {
		return 0, nil, nil, err
	}
	request.Header.Set("Authorization", "Bearer "+apiKey)
	request.Header.Set("Accept", "application/json")
	if body != nil {
		// Сообщения на русском — тело обязано уехать в UTF-8.
		request.Header.Set("Content-Type", "application/json; charset=utf-8")
	}

	response, err := client.Do(request)
	if err != nil {
		return 0, nil, nil, err
	}
	defer response.Body.Close()

	payload, err := io.ReadAll(response.Body)
	if err != nil {
		return 0, nil, nil, err
	}
	return response.StatusCode, payload, response.Header, nil
}

// retryAfter говорит, сколько ждать после 429. Мусор и слишком большие значения
// не берём на веру: ноль означал бы busy-loop, 40+ минут — зависший процесс.
// Ноль на выходе значит «ждать бессмысленно долго, сдавайся».
func retryAfter(header http.Header) time.Duration {
	seconds, err := strconv.Atoi(header.Get("Retry-After"))
	if err != nil || seconds <= 0 {
		seconds = 20
	}
	delay := time.Duration(seconds) * time.Second
	if delay > MaxRetryDelay {
		return 0
	}
	return delay
}

// ── Клиент ────────────────────────────────────────────────────────────────────

// ListModels возвращает список моделей сервера (GET /api/AiChat/models).
//
// Ключ нужен (без него 401), но квоту платного /send этот вызов не расходует:
// в ответе нет даже заголовка X-Atlorium-Sandbox.
func ListModels() ([]AiModelInfo, error) {
	status, body, _, err := do(http.MethodGet, "/api/AiChat/models", nil)
	if err != nil {
		return nil, err
	}
	if status != http.StatusOK {
		return nil, &APIError{Status: status, Body: string(body)}
	}

	var models []AiModelInfo
	if err := json.Unmarshal(body, &models); err != nil {
		return nil, err
	}
	return models, nil
}

// Send отправляет сообщение (POST /api/AiChat/send) — единственный платный вызов.
//
// sessionID = nil создаёт новую сессию; передав sessionId из прошлого ответа,
// можно продолжить диалог — модель увидит предыдущие сообщения.
func Send(message string, model, sessionID *string) (*AiChatResponse, error) {
	payload, err := json.Marshal(AiChatRequest{Message: message, SessionID: sessionID, Model: model})
	if err != nil {
		return nil, err
	}

	for attempt := 0; attempt <= MaxRetries; attempt++ {
		status, body, header, err := do(http.MethodPost, "/api/AiChat/send", payload)
		if err != nil {
			return nil, err
		}

		// 429 — не поломка, а реальный лимит продукта.
		if status == http.StatusTooManyRequests && attempt < MaxRetries {
			delay := retryAfter(header)
			if delay == 0 {
				return nil, &APIError{Status: 429, Body: "лимит запросов исчерпан, повторите позже"}
			}
			fmt.Fprintf(os.Stderr, "  ... лимит запросов, пауза %s\n", delay)
			time.Sleep(delay)
			continue
		}

		if status != http.StatusOK {
			return nil, &APIError{Status: status, Body: string(body)}
		}

		var answer AiChatResponse
		if err := json.Unmarshal(body, &answer); err != nil {
			return nil, err
		}
		return &answer, nil
	}

	return nil, &APIError{Status: 429, Body: "лимит запросов исчерпан, повторите позже"}
}

// GetSession возвращает сведения о сессии (GET /api/AiChat/session/{id}).
//
// nil без ошибки означает 404. В песочнице это штатный случай: мок не сохраняет
// историю, поэтому 404 приходит сразу после /send.
func GetSession(sessionID string) (*AiChatSession, error) {
	status, body, _, err := do(http.MethodGet, "/api/AiChat/session/"+url.PathEscape(sessionID), nil)
	if err != nil {
		return nil, err
	}
	if status == http.StatusNotFound {
		return nil, nil
	}
	if status != http.StatusOK {
		return nil, &APIError{Status: status, Body: string(body)}
	}

	var session AiChatSession
	if err := json.Unmarshal(body, &session); err != nil {
		return nil, err
	}
	return &session, nil
}

// DeleteSession стирает историю сессии. false без ошибки — сессии уже нет (404).
func DeleteSession(sessionID string) (bool, error) {
	status, body, _, err := do(http.MethodDelete, "/api/AiChat/session/"+url.PathEscape(sessionID), nil)
	if err != nil {
		return false, err
	}
	if status == http.StatusNotFound {
		return false, nil
	}
	if status != http.StatusOK {
		return false, &APIError{Status: status, Body: string(body)}
	}
	return true, nil
}

// ── Применение: суммаризация текста ───────────────────────────────────────────
// Ценность не в том, чтобы дёрнуть /send, а в том, чтобы дёрнуть его ОДИН раз и
// по делу. Две вещи, которые делает эта функция и не делает наивный пример:
//   1) не хардкодит модель — берёт первую ДОСТУПНУЮ из /models;
//   2) считает длину ДО отправки и не тратит платный вызов на заведомо провальный.

// EstimateTokens — грубая оценка числа токенов: символы / 4.
//
// Это ПРИБЛИЗИТЕЛЬНАЯ эвристика, а не токенизатор. Для русского текста реальное
// число токенов обычно выше оценки, для английского — близко к ней. Точную цифру
// знает только модель, но нам она и не нужна: задача — отсечь заведомо длинный
// текст до платного вызова, а не посчитать биллинг.
//
// Считаем руны, а не байты: в UTF-8 кириллица занимает по два байта, и len(string)
// завысил бы длину вдвое.
func EstimateTokens(text string) int {
	runes := len([]rune(text))
	return (runes + 3) / 4
}

// PickModel возвращает первую модель с IsAvailable == true (или nil, если таких нет).
//
// Модель НЕ хардкодится сознательно: состав доступных моделей меняется на стороне
// сервера, и пример, зашивший конкретный id, однажды тихо сломается.
func PickModel(models []AiModelInfo) *AiModelInfo {
	for i := range models {
		if models[i].IsAvailable {
			return &models[i]
		}
	}
	return nil
}

// Summarize — прикладной сценарий целиком. Возвращает код выхода процесса.
func Summarize(text string) int {
	models, err := ListModels()
	if err != nil {
		fmt.Fprintln(os.Stderr, "Ошибка:", err)
		return 1
	}

	fmt.Println("Доступные модели (GET /api/AiChat/models):")
	for _, model := range models {
		mark, state := "[ ]", "недоступна"
		if model.IsAvailable {
			mark, state = "[*]", "доступна"
		}
		fmt.Printf("  %s %-9s %s — вход до %d токенов, %s\n",
			mark, model.ID, model.Name, model.MaxInputTokens, state)
	}

	chosen := PickModel(models)
	if chosen == nil {
		fmt.Fprintln(os.Stderr, "\nНи одна модель сейчас не доступна (isAvailable=false у всех). "+
			"Отправлять нечего — выходим.")
		return 1
	}

	fmt.Printf("\nВыбрана: %s (%s) — первая доступная в списке.\n", chosen.ID, chosen.Name)
	fmt.Printf("  Лимит входа: %d токенов\n", chosen.MaxInputTokens)

	// Цена запроса складывается из двух частей: ставки за сам запрос (одинаковой для всех
	// моделей) и стоимости токенов ВЫБРАННОЙ модели. Вторую часть модель объявляет сама —
	// полями inputPricePer1kTokens / outputPricePer1kTokens (кредитов за 1000 токенов).
	// Их стоит показать пользователю ДО отправки: у разных моделей она отличается в разы.
	if chosen.InputPricePer1kTokens > 0 || chosen.OutputPricePer1kTokens > 0 {
		fmt.Println("  Токены этой модели тарифицируются отдельно — см. https://atlorium.com/pricing")
	}
	if chosen.FreeQuotaEligible {
		fmt.Println("  Дневная квота тарифа покрывает запросы к этой модели")
	}

	prompt := "Сделай краткую выжимку текста ниже: 2–3 предложения, только суть, " +
		"без вступлений и повторов.\n\nТекст:\n" + text

	// ── Проверка длины ДО отправки. /send — платный вызов, и слать в него заведомо
	//    неподъёмный текст значит потратить деньги и квоту на заведомо провальном запросе.
	//    Единственный лимит длины сообщения — токенный и зависит от модели: сервер сверяет
	//    ввод с maxInputTokens той модели, которую вы указали в запросе.
	chars := len([]rune(prompt))
	tokens := EstimateTokens(prompt)
	fmt.Println("\nПроверка длины до отправки:")
	fmt.Printf("  Символов в запросе: %d\n", chars)
	fmt.Printf("  Оценка токенов: ~%d (приблизительно: символы / 4)\n", tokens)

	if tokens > chosen.MaxInputTokens {
		fmt.Fprintf(os.Stderr, "\nТекст длиннее лимита модели %s (~%d > %d токенов). "+
			"Сократите его или разбейте на части — запрос не отправлен, деньги не потрачены.\n",
			chosen.ID, tokens, chosen.MaxInputTokens)
		return 1
	}

	fmt.Println("  Влезает — отправляем.")

	// ── Единственный платный вызов за весь прогон.
	answer, err := Send(prompt, &chosen.ID, nil)
	if err != nil {
		fmt.Fprintln(os.Stderr, "Ошибка:", err)
		return 1
	}

	fmt.Println("\n── Выжимка ───────────────────────────────────────────────────────")
	fmt.Println(answer.Reply)
	fmt.Println("──────────────────────────────────────────────────────────────────")
	fmt.Printf("\nМодель: %s\n", answer.Model)
	fmt.Printf("Токены: prompt %d + completion %d = %d\n",
		answer.PromptTokens, answer.CompletionTokens, answer.TotalTokens)
	fmt.Printf("Сессия: %s\n", answer.SessionID)

	// ── Работа с сессией. Передав этот sessionId в следующий /send, диалог можно
	//    продолжить: «а теперь короче», «переведи на английский» — модель увидит контекст.
	session, err := GetSession(answer.SessionID)
	if err != nil {
		fmt.Fprintln(os.Stderr, "Ошибка:", err)
		return 1
	}
	if session == nil {
		fmt.Println("\nGET /api/AiChat/session/{id} → 404: в песочнице история сессии не сохраняется.")
		fmt.Println("С боевым ключом сессия живёт час и доступна по этому же запросу — а sessionId")
		fmt.Println("можно передать в следующий /send и продолжить диалог.")
	} else {
		fmt.Printf("\nСессия: сообщений %d, создана %s, последняя активность %s.\n",
			session.MessageCount, session.CreatedAt, session.LastActivityAt)
	}

	if apiKey == SandboxKey {
		fmt.Println("\nНапоминание: reply выше — заглушка песочницы, а не работа модели. Боевой ключ")
		fmt.Println("вернёт настоящую выжимку тем же кодом.")
	}

	return 0
}

const defaultText = "Клиент пишет, что после обновления мобильного приложения до версии 4.2 перестали " +
	"приходить push-уведомления о новых заказах. Проблема воспроизводится только на " +
	"Android 14, на iOS всё работает штатно. Клиент уже переустановил приложение, " +
	"проверил разрешения в настройках системы и перезагрузил телефон — не помогло. " +
	"В логах нашего сервера видно, что уведомления отправляются и FCM возвращает " +
	"успешный статус доставки. Клиент просит решить вопрос срочно: из-за пропущенных " +
	"заказов он теряет выручку, вчера сорвалась доставка на крупную сумму. Он также " +
	"спрашивает про компенсацию и предупреждает, что уйдёт к конкурентам, если проблема " +
	"не будет решена до конца недели."

func main() {
	if apiKey == SandboxKey {
		fmt.Println("Демо-ключ: ответ модели — заглушка (мок). Реальная ИИ-модель не вызывается, " +
			"токены не тратятся.")
		fmt.Println()
	}

	text := defaultText
	if len(os.Args) > 1 {
		text = os.Args[1]
	}

	os.Exit(Summarize(text))
}
