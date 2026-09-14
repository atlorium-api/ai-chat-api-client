<?php

/**
 * Клиент API ИИ-чата Atlorium — суммаризация текста нейросетью через HTTP.
 *
 * Запуск (работает сразу, без регистрации — на демо-ключе):
 *   php main.php
 *   php main.php "свой текст для суммаризации"
 *
 * Боевой ключ: получить на https://atlorium.com и положить в переменную окружения
 * ATLORIUM_API_KEY. Код при этом не меняется.
 */

declare(strict_types=1);

/**
 * Публичный демо-ключ. С ним /send возвращает МОК: реальная ИИ-модель не вызывается,
 * токены не тратятся, а в поле reply лежит заглушка, которая честно об этом сообщает.
 * Механику (выбор модели, сессии, учёт токенов) на нём отладить можно, саму
 * суммаризацию — нет. Для настоящих ответов модели нужен боевой ключ.
 */
const SANDBOX_KEY = 'ak_sandbox_demo_mockdata_v1';

const TIMEOUT = 60;

/**
 * Повтор после 429 — ровно один раз. Лимит у ИИ-чата самый жёсткий в Atlorium,
 * поэтому повторять запросы подряд бессмысленно.
 */
const MAX_RETRIES = 1;

/**
 * Потолок ожидания. Исчерпав ЧАСОВОЙ лимит, сервер честно просит подождать 40+ минут.
 * Спать столько нельзя — процесс (и CI-джоб) просто зависнет. Дольше потолка не ждём,
 * а честно сообщаем «квота исчерпана» и выходим.
 */
const MAX_RETRY_DELAY = 120;

/** Ошибка API: HTTP-код разложен в человекочитаемую причину. */
final class AtloriumError extends RuntimeException
{
    private const REASONS = [
        400 => 'Сообщение пустое, длиннее лимита выбранной модели или указана неизвестная модель',
        401 => 'API-ключ отсутствует, просрочен или недействителен',
        402 => 'Недостаточно кредитов: перед вызовом модели удерживается максимально возможная '
            . 'стоимость запроса — пополните на https://atlorium.com',
        404 => 'Сессия не найдена или истекла',
        429 => 'Превышен лимит запросов — повторите позже',
        500 => 'Ошибка ИИ-сервиса при обработке запроса (в том числе исчерпан лимит сообщений в сессии)',
        503 => 'Выбранная модель временно недоступна — возьмите другую из GET /api/AiChat/models '
            . '(за сбой на своей стороне мы не списываем деньги)',
    ];

    public function __construct(public readonly int $status, string $body)
    {
        $reason = self::REASONS[$status] ?? 'Неизвестная ошибка';
        parent::__construct(sprintf(
            'HTTP %d: %s. Ответ сервера: %s',
            $status,
            $reason,
            mb_substr($body, 0, 200)
        ));
    }
}

final class AiChatClient
{
    private string $apiKey;
    private string $baseUrl;

    public function __construct(?string $apiKey = null, ?string $baseUrl = null)
    {
        $this->apiKey = $apiKey ?? (getenv('ATLORIUM_API_KEY') ?: SANDBOX_KEY);
        $this->baseUrl = $baseUrl ?? (getenv('ATLORIUM_BASE_URL') ?: 'https://atlorium.com');
    }

    public function isSandbox(): bool
    {
        return $this->apiKey === SANDBOX_KEY;
    }

    /**
     * Выполняет запрос и возвращает [код, тело, заголовки]. Код отдаётся наружу,
     * потому что 404 на сессии — штатный случай, а не ошибка.
     *
     * @return array{0: int, 1: string, 2: array<string, string>}
     */
    private function request(string $method, string $path, ?string $jsonBody = null): array
    {
        $headers = [
            'Authorization: Bearer ' . $this->apiKey,
            'Accept: application/json',
        ];
        if ($jsonBody !== null) {
            // Сообщения на русском — тело обязано уехать в UTF-8.
            $headers[] = 'Content-Type: application/json; charset=utf-8';
        }

        $responseHeaders = [];

        $curl = curl_init($this->baseUrl . $path);
        curl_setopt_array($curl, [
            CURLOPT_RETURNTRANSFER => true,
            CURLOPT_TIMEOUT => TIMEOUT,
            CURLOPT_CUSTOMREQUEST => $method,
            CURLOPT_HTTPHEADER => $headers,
            CURLOPT_HEADERFUNCTION => function ($_curl, string $line) use (&$responseHeaders): int {
                $parts = explode(':', $line, 2);
                if (count($parts) === 2) {
                    $responseHeaders[strtolower(trim($parts[0]))] = trim($parts[1]);
                }
                return strlen($line);
            },
        ]);

        if ($jsonBody !== null) {
            curl_setopt($curl, CURLOPT_POSTFIELDS, $jsonBody);
        }

        $body = curl_exec($curl);
        if ($body === false) {
            $error = curl_error($curl);
            curl_close($curl);
            throw new RuntimeException("Сетевая ошибка: {$error}");
        }

        $status = curl_getinfo($curl, CURLINFO_RESPONSE_CODE);
        curl_close($curl);

        return [$status, (string) $body, $responseHeaders];
    }

    /**
     * Сколько ждать после 429. Мусор и слишком большие значения не берём на веру:
     * ноль означал бы busy-loop, 40+ минут — зависший процесс. Ноль на выходе
     * значит «ждать бессмысленно долго, сдавайся».
     *
     * @param array<string, string> $headers
     */
    private function retryAfter(array $headers): int
    {
        $seconds = (int) ($headers['retry-after'] ?? 0);
        if ($seconds <= 0) {
            $seconds = 20;
        }
        return $seconds <= MAX_RETRY_DELAY ? $seconds : 0;
    }

    /**
     * GET /api/AiChat/models — список моделей сервера.
     *
     * Ключ нужен (без него 401), но квоту платного /send этот вызов не расходует:
     * в ответе нет даже заголовка X-Atlorium-Sandbox.
     *
     * @return list<array<string, mixed>>
     */
    public function listModels(): array
    {
        [$status, $body] = $this->request('GET', '/api/AiChat/models');
        if ($status !== 200) {
            throw new AtloriumError($status, $body);
        }

        return json_decode($body, true, 512, JSON_THROW_ON_ERROR);
    }

    /**
     * POST /api/AiChat/send — единственный платный вызов сервиса.
     *
     * $sessionId = null создаёт новую сессию; передав sessionId из прошлого ответа,
     * можно продолжить диалог — модель увидит предыдущие сообщения.
     *
     * @return array<string, mixed>
     */
    public function send(string $message, ?string $model = null, ?string $sessionId = null): array
    {
        // JSON_UNESCAPED_UNICODE — чтобы русский текст ушёл как есть, а не как \uXXXX.
        $payload = json_encode(
            ['message' => $message, 'sessionId' => $sessionId, 'model' => $model],
            JSON_THROW_ON_ERROR | JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES
        );

        for ($attempt = 0; $attempt <= MAX_RETRIES; $attempt++) {
            [$status, $body, $headers] = $this->request('POST', '/api/AiChat/send', $payload);

            // 429 — не поломка, а реальный лимит продукта.
            if ($status === 429 && $attempt < MAX_RETRIES) {
                $delay = $this->retryAfter($headers);
                if ($delay === 0) {
                    throw new AtloriumError(429, 'лимит запросов исчерпан, повторите позже');
                }
                fwrite(STDERR, "  ... лимит запросов, пауза {$delay} с\n");
                sleep($delay);
                continue;
            }

            if ($status !== 200) {
                throw new AtloriumError($status, $body);
            }

            return json_decode($body, true, 512, JSON_THROW_ON_ERROR);
        }

        throw new AtloriumError(429, 'лимит запросов исчерпан, повторите позже');
    }

    /**
     * GET /api/AiChat/session/{id} — сколько сообщений в сессии и когда была активна.
     *
     * null означает 404. В песочнице это штатный случай: мок не сохраняет историю,
     * поэтому 404 приходит сразу после /send.
     *
     * @return array<string, mixed>|null
     */
    public function getSession(string $sessionId): ?array
    {
        [$status, $body] = $this->request('GET', '/api/AiChat/session/' . rawurlencode($sessionId));

        if ($status === 404) {
            return null;
        }
        if ($status !== 200) {
            throw new AtloriumError($status, $body);
        }

        return json_decode($body, true, 512, JSON_THROW_ON_ERROR);
    }

    /** DELETE /api/AiChat/session/{id} — стереть историю. false, если сессии уже нет. */
    public function deleteSession(string $sessionId): bool
    {
        [$status, $body] = $this->request('DELETE', '/api/AiChat/session/' . rawurlencode($sessionId));

        if ($status === 404) {
            return false;
        }
        if ($status !== 200) {
            throw new AtloriumError($status, $body);
        }

        return true;
    }
}

// ── Применение: суммаризация текста ───────────────────────────────────────────
// Ценность не в том, чтобы дёрнуть /send, а в том, чтобы дёрнуть его ОДИН раз и
// по делу. Две вещи, которые делает этот код и не делает наивный пример:
//   1) не хардкодит модель — берёт первую ДОСТУПНУЮ из /models;
//   2) считает длину ДО отправки и не тратит платный вызов на заведомо провальный.

/**
 * Грубая оценка числа токенов: символы / 4.
 *
 * Это ПРИБЛИЗИТЕЛЬНАЯ эвристика, а не токенизатор. Для русского текста реальное
 * число токенов обычно выше оценки, для английского — близко к ней. Точную цифру
 * знает только модель, но нам она и не нужна: задача — отсечь заведомо длинный
 * текст до платного вызова, а не посчитать биллинг.
 *
 * mb_strlen, а не strlen: в UTF-8 кириллица занимает по два байта, и strlen
 * завысил бы длину вдвое.
 */
function estimateTokens(string $text): int
{
    return (int) ceil(mb_strlen($text) / 4);
}

/**
 * Первая модель с isAvailable === true (или null, если таких нет).
 *
 * Модель НЕ хардкодится сознательно: состав доступных моделей меняется на стороне
 * сервера, и пример, зашивший конкретный id, однажды тихо сломается.
 *
 * @param list<array<string, mixed>> $models
 * @return array<string, mixed>|null
 */
function pickModel(array $models): ?array
{
    foreach ($models as $model) {
        if ($model['isAvailable'] ?? false) {
            return $model;
        }
    }
    return null;
}

function summarize(AiChatClient $client, string $text): int
{
    $models = $client->listModels();

    echo "Доступные модели (GET /api/AiChat/models):\n";
    foreach ($models as $model) {
        $available = (bool) ($model['isAvailable'] ?? false);
        printf(
            "  %s %s %s — вход до %d токенов, %s\n",
            $available ? '[*]' : '[ ]',
            str_pad((string) $model['id'], 9),
            (string) $model['name'],
            (int) $model['maxInputTokens'],
            $available ? 'доступна' : 'недоступна'
        );
    }

    $chosen = pickModel($models);
    if ($chosen === null) {
        fwrite(STDERR, "\nНи одна модель сейчас не доступна (isAvailable=false у всех). "
            . "Отправлять нечего — выходим.\n");
        return 1;
    }

    $maxInput = (int) $chosen['maxInputTokens'];

    printf("\nВыбрана: %s (%s) — первая доступная в списке.\n", $chosen['id'], $chosen['name']);
    printf("  Лимит входа: %d токенов\n", $maxInput);

    // Цена запроса складывается из двух частей: ставки за сам запрос (одинаковой для всех
    // моделей) и стоимости токенов ВЫБРАННОЙ модели. Вторую часть модель объявляет сама —
    // полями inputPricePer1kTokens / outputPricePer1kTokens (кредитов за 1000 токенов).
    // Их стоит показать пользователю ДО отправки: у разных моделей она отличается в разы.
    $inputPrice = (float) ($chosen['inputPricePer1kTokens'] ?? 0);
    $outputPrice = (float) ($chosen['outputPricePer1kTokens'] ?? 0);
    if ($inputPrice > 0 || $outputPrice > 0) {
        echo "  Токены этой модели тарифицируются отдельно — см. https://atlorium.com/pricing\n";
    }
    if ($chosen['freeQuotaEligible'] ?? false) {
        echo "  Дневная квота тарифа покрывает запросы к этой модели\n";
    }

    $prompt = "Сделай краткую выжимку текста ниже: 2–3 предложения, только суть, "
        . "без вступлений и повторов.\n\nТекст:\n" . $text;

    // ── Проверка длины ДО отправки. /send — платный вызов, и слать в него заведомо
    //    неподъёмный текст значит потратить деньги и квоту на заведомо провальном запросе.
    //    Единственный лимит длины сообщения — токенный и зависит от модели: сервер сверяет
    //    ввод с maxInputTokens той модели, которую вы указали в запросе.
    $chars = mb_strlen($prompt);
    $tokens = estimateTokens($prompt);

    echo "\nПроверка длины до отправки:\n";
    printf("  Символов в запросе: %d\n", $chars);
    printf("  Оценка токенов: ~%d (приблизительно: символы / 4)\n", $tokens);

    if ($tokens > $maxInput) {
        fwrite(STDERR, sprintf(
            "\nТекст длиннее лимита модели %s (~%d > %d токенов). Сократите его или разбейте "
            . "на части — запрос не отправлен, деньги не потрачены.\n",
            $chosen['id'],
            $tokens,
            $maxInput
        ));
        return 1;
    }

    echo "  Влезает — отправляем.\n";

    // ── Единственный платный вызов за весь прогон.
    $answer = $client->send($prompt, (string) $chosen['id']);

    echo "\n── Выжимка ───────────────────────────────────────────────────────\n";
    echo $answer['reply'] . "\n";
    echo "──────────────────────────────────────────────────────────────────\n";
    printf("\nМодель: %s\n", $answer['model']);
    printf(
        "Токены: prompt %d + completion %d = %d\n",
        $answer['promptTokens'],
        $answer['completionTokens'],
        $answer['totalTokens']
    );
    printf("Сессия: %s\n", $answer['sessionId']);

    // ── Работа с сессией. Передав этот sessionId в следующий /send, диалог можно
    //    продолжить: «а теперь короче», «переведи на английский» — модель увидит контекст.
    $session = $client->getSession((string) $answer['sessionId']);
    if ($session === null) {
        echo "\nGET /api/AiChat/session/{id} → 404: в песочнице история сессии не сохраняется.\n";
        echo "С боевым ключом сессия живёт час и доступна по этому же запросу — а sessionId\n";
        echo "можно передать в следующий /send и продолжить диалог.\n";
    } else {
        printf(
            "\nСессия: сообщений %d, создана %s, последняя активность %s.\n",
            $session['messageCount'],
            $session['createdAt'],
            $session['lastActivityAt']
        );
    }

    if ($client->isSandbox()) {
        echo "\nНапоминание: reply выше — заглушка песочницы, а не работа модели. Боевой ключ\n";
        echo "вернёт настоящую выжимку тем же кодом.\n";
    }

    return 0;
}

// ── Демонстрация ─────────────────────────────────────────────────────────────

const DEFAULT_TEXT = 'Клиент пишет, что после обновления мобильного приложения до версии 4.2 перестали '
    . 'приходить push-уведомления о новых заказах. Проблема воспроизводится только на '
    . 'Android 14, на iOS всё работает штатно. Клиент уже переустановил приложение, '
    . 'проверил разрешения в настройках системы и перезагрузил телефон — не помогло. '
    . 'В логах нашего сервера видно, что уведомления отправляются и FCM возвращает '
    . 'успешный статус доставки. Клиент просит решить вопрос срочно: из-за пропущенных '
    . 'заказов он теряет выручку, вчера сорвалась доставка на крупную сумму. Он также '
    . 'спрашивает про компенсацию и предупреждает, что уйдёт к конкурентам, если проблема '
    . 'не будет решена до конца недели.';

$client = new AiChatClient();

if ($client->isSandbox()) {
    echo "Демо-ключ: ответ модели — заглушка (мок). Реальная ИИ-модель не вызывается, "
        . "токены не тратятся.\n\n";
}

$text = $argv[1] ?? DEFAULT_TEXT;

try {
    exit(summarize($client, $text));
} catch (AtloriumError $error) {
    fwrite(STDERR, "Ошибка: {$error->getMessage()}\n");
    exit(1);
}
