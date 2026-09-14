// Клиент API ИИ-чата Atlorium — суммаризация текста нейросетью через HTTP.
//
// Запуск (работает сразу, без регистрации — на демо-ключе):
//     dotnet run
//     dotnet run -- "свой текст для суммаризации"
//
// Боевой ключ: получить на https://atlorium.com и положить в переменную окружения
// ATLORIUM_API_KEY. Код при этом не меняется.

using System.Net;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;

// Публичный демо-ключ. С ним /send возвращает МОК: реальная ИИ-модель не вызывается,
// токены не тратятся, а в поле reply лежит заглушка, которая честно об этом сообщает.
// Механику (выбор модели, сессии, учёт токенов) на нём отладить можно, саму
// суммаризацию — нет. Для настоящих ответов модели нужен боевой ключ.
const string SandboxKey = "ak_sandbox_demo_mockdata_v1";

// Сообщения и ответы — на русском. Без этого консоль Windows покажет мусор.
Console.OutputEncoding = Encoding.UTF8;

var apiKey = Environment.GetEnvironmentVariable("ATLORIUM_API_KEY") ?? SandboxKey;
var baseUrl = Environment.GetEnvironmentVariable("ATLORIUM_BASE_URL") ?? "https://atlorium.com";

using var http = new HttpClient
{
    BaseAddress = new Uri(baseUrl),
    Timeout = TimeSpan.FromSeconds(60),
};
http.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", apiKey);
http.DefaultRequestHeaders.Accept.Add(new MediaTypeWithQualityHeaderValue("application/json"));

var client = new AiChatClient(http);

if (apiKey == SandboxKey)
{
    Console.WriteLine("Демо-ключ: ответ модели — заглушка (мок). Реальная ИИ-модель не вызывается, "
                      + "токены не тратятся.\n");
}

const string DefaultText =
    "Клиент пишет, что после обновления мобильного приложения до версии 4.2 перестали "
    + "приходить push-уведомления о новых заказах. Проблема воспроизводится только на "
    + "Android 14, на iOS всё работает штатно. Клиент уже переустановил приложение, "
    + "проверил разрешения в настройках системы и перезагрузил телефон — не помогло. "
    + "В логах нашего сервера видно, что уведомления отправляются и FCM возвращает "
    + "успешный статус доставки. Клиент просит решить вопрос срочно: из-за пропущенных "
    + "заказов он теряет выручку, вчера сорвалась доставка на крупную сумму. Он также "
    + "спрашивает про компенсацию и предупреждает, что уйдёт к конкурентам, если проблема "
    + "не будет решена до конца недели.";

var text = args.Length > 0 ? args[0] : DefaultText;

try
{
    return await Summarizer.RunAsync(client, text, apiKey == SandboxKey);
}
catch (AtloriumException error)
{
    Console.Error.WriteLine($"Ошибка: {error.Message}");
    return 1;
}

// ── Клиент ───────────────────────────────────────────────────────────────────

/// <summary>Ошибка API: HTTP-код разложен в человекочитаемую причину.</summary>
public sealed class AtloriumException(HttpStatusCode status, string body)
    : Exception($"HTTP {(int)status}: {Explain(status)}. Ответ сервера: {body[..Math.Min(200, body.Length)]}")
{
    public HttpStatusCode Status { get; } = status;

    private static string Explain(HttpStatusCode status) => (int)status switch
    {
        400 => "Сообщение пустое, длиннее лимита выбранной модели или указана неизвестная модель",
        401 => "API-ключ отсутствует, просрочен или недействителен",
        402 => "Недостаточно кредитов: перед вызовом модели удерживается максимально возможная "
               + "стоимость запроса — пополните на https://atlorium.com",
        404 => "Сессия не найдена или истекла",
        429 => "Превышен лимит запросов — повторите позже",
        500 => "Ошибка ИИ-сервиса при обработке запроса (в том числе исчерпан лимит сообщений в сессии)",
        503 => "Выбранная модель временно недоступна — возьмите другую из GET /api/AiChat/models "
               + "(за сбой на своей стороне мы не списываем деньги)",
        _ => "Неизвестная ошибка",
    };
}

public sealed class AiChatClient(HttpClient http)
{
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web);

    /// <summary>
    /// Повтор после 429 — ровно один раз. Лимит у ИИ-чата самый жёсткий в Atlorium,
    /// поэтому повторять запросы подряд бессмысленно.
    /// </summary>
    private const int MaxRetries = 1;

    /// <summary>
    /// Потолок ожидания. Исчерпав ЧАСОВОЙ лимит, сервер честно просит подождать 40+ минут.
    /// Спать столько нельзя — процесс (и CI-джоб) просто зависнет. Дольше потолка не ждём,
    /// а честно сообщаем «квота исчерпана» и выходим.
    /// </summary>
    private static readonly TimeSpan MaxRetryDelay = TimeSpan.FromSeconds(120);

    /// <summary>
    /// GET /api/AiChat/models — список моделей сервера.
    ///
    /// Ключ нужен (без него 401), но квоту платного /send этот вызов не расходует:
    /// в ответе нет даже заголовка X-Atlorium-Sandbox.
    /// </summary>
    public async Task<IReadOnlyList<AiModelInfo>> ListModelsAsync()
    {
        using var response = await http.GetAsync("/api/AiChat/models");
        await EnsureSuccessAsync(response);

        var json = await response.Content.ReadAsStringAsync();
        return JsonSerializer.Deserialize<List<AiModelInfo>>(json, JsonOptions)
               ?? throw new InvalidOperationException("Пустой ответ API.");
    }

    /// <summary>
    /// POST /api/AiChat/send — единственный платный вызов сервиса.
    ///
    /// sessionId = null создаёт новую сессию; передав sessionId из прошлого ответа,
    /// можно продолжить диалог — модель увидит предыдущие сообщения.
    /// </summary>
    public async Task<AiChatResponse> SendAsync(string message, string? model = null, string? sessionId = null)
    {
        var payload = JsonSerializer.Serialize(
            new AiChatRequest(message, sessionId, model), JsonOptions);

        for (var attempt = 0; attempt <= MaxRetries; attempt++)
        {
            // Явный UTF-8: текст на русском, и без указания кодировки тело уехало бы
            // в кодировке платформы.
            using var content = new StringContent(payload, Encoding.UTF8, "application/json");
            using var response = await http.PostAsync("/api/AiChat/send", content);

            // 429 — не поломка, а реальный лимит продукта.
            if (response.StatusCode == HttpStatusCode.TooManyRequests && attempt < MaxRetries)
            {
                var delay = RetryAfter(response);
                if (delay == TimeSpan.Zero)
                {
                    throw new AtloriumException(HttpStatusCode.TooManyRequests,
                        "лимит запросов исчерпан, повторите позже");
                }

                Console.Error.WriteLine($"  ... лимит запросов, пауза {delay.TotalSeconds:0} с");
                await Task.Delay(delay);
                continue;
            }

            await EnsureSuccessAsync(response);

            var json = await response.Content.ReadAsStringAsync();
            return JsonSerializer.Deserialize<AiChatResponse>(json, JsonOptions)
                   ?? throw new InvalidOperationException("Пустой ответ API.");
        }

        throw new AtloriumException(HttpStatusCode.TooManyRequests,
            "лимит запросов исчерпан, повторите позже");
    }

    /// <summary>
    /// GET /api/AiChat/session/{id} — сколько сообщений в сессии и когда была активна.
    ///
    /// null означает 404. В песочнице это штатный случай: мок не сохраняет историю,
    /// поэтому 404 приходит сразу после /send.
    /// </summary>
    public async Task<AiChatSession?> GetSessionAsync(string sessionId)
    {
        using var response = await http.GetAsync($"/api/AiChat/session/{Uri.EscapeDataString(sessionId)}");
        if (response.StatusCode == HttpStatusCode.NotFound)
        {
            return null;
        }

        await EnsureSuccessAsync(response);

        var json = await response.Content.ReadAsStringAsync();
        return JsonSerializer.Deserialize<AiChatSession>(json, JsonOptions);
    }

    /// <summary>DELETE /api/AiChat/session/{id} — стереть историю. false, если сессии уже нет.</summary>
    public async Task<bool> DeleteSessionAsync(string sessionId)
    {
        using var response = await http.DeleteAsync($"/api/AiChat/session/{Uri.EscapeDataString(sessionId)}");
        if (response.StatusCode == HttpStatusCode.NotFound)
        {
            return false;
        }

        await EnsureSuccessAsync(response);
        return true;
    }

    /// <summary>
    /// Сколько ждать после 429. Мусор и слишком большие значения не берём на веру:
    /// ноль означал бы busy-loop, 40+ минут — зависший процесс. TimeSpan.Zero на выходе
    /// значит «ждать бессмысленно долго, сдавайся».
    /// </summary>
    private static TimeSpan RetryAfter(HttpResponseMessage response)
    {
        var seconds = response.Headers.RetryAfter?.Delta ?? TimeSpan.Zero;
        if (seconds <= TimeSpan.Zero)
        {
            seconds = TimeSpan.FromSeconds(20);
        }
        return seconds <= MaxRetryDelay ? seconds : TimeSpan.Zero;
    }

    private static async Task EnsureSuccessAsync(HttpResponseMessage response)
    {
        if (!response.IsSuccessStatusCode)
        {
            throw new AtloriumException(response.StatusCode, await response.Content.ReadAsStringAsync());
        }
    }
}

// ── Модель запроса и ответа ──────────────────────────────────────────────────

/// <summary>Тело POST /api/AiChat/send.</summary>
public sealed record AiChatRequest(string Message, string? SessionId, string? Model);

/// <summary>Модель из GET /api/AiChat/models.</summary>
public sealed record AiModelInfo
{
    public string Id { get; init; } = "";
    public string Name { get; init; } = "";
    public string Description { get; init; } = "";
    public int ContextWindowTokens { get; init; }
    public int MaxInputTokens { get; init; }
    public int MaxOutputTokens { get; init; }
    public int MaxTokens { get; init; }

    /// <summary>Сервер может отключить модель — тогда /send с её id не пройдёт.</summary>
    public bool IsAvailable { get; init; }

    /// <summary>true — модель крутится на сервере Atlorium, переписка не уходит наружу.</summary>
    public bool IsLocal { get; init; }

    // Ценовые поля необязательные: если сервер их не прислал, System.Text.Json оставит
    // значения по умолчанию (0 / false) — ровно тот безопасный дефолт, который нужен.

    /// <summary>Цена входных токенов модели, кредитов за 1000. 0 — токены не тарифицируются.</summary>
    public decimal InputPricePer1kTokens { get; init; }

    /// <summary>Цена выходных токенов модели, кредитов за 1000. 0 — токены не тарифицируются.</summary>
    public decimal OutputPricePer1kTokens { get; init; }

    /// <summary>Покрывает ли запросы к этой модели дневная квота тарифа.</summary>
    public bool FreeQuotaEligible { get; init; }
}

/// <summary>Ответ POST /api/AiChat/send.</summary>
public sealed record AiChatResponse
{
    public string SessionId { get; init; } = "";
    public string Reply { get; init; } = "";
    public string Model { get; init; } = "";
    public int PromptTokens { get; init; }
    public int CompletionTokens { get; init; }
    public int TotalTokens { get; init; }
    public DateTimeOffset CreatedAt { get; init; }
}

/// <summary>Ответ GET /api/AiChat/session/{id}.</summary>
public sealed record AiChatSession
{
    public string SessionId { get; init; } = "";
    public int MessageCount { get; init; }
    public DateTimeOffset CreatedAt { get; init; }
    public DateTimeOffset LastActivityAt { get; init; }
}

// ── Применение: суммаризация текста ──────────────────────────────────────────
// Ценность не в том, чтобы дёрнуть /send, а в том, чтобы дёрнуть его ОДИН раз и
// по делу. Две вещи, которые делает этот код и не делает наивный пример:
//   1) не хардкодит модель — берёт первую ДОСТУПНУЮ из /models;
//   2) считает длину ДО отправки и не тратит платный вызов на заведомо провальный.

public static class Summarizer
{
    /// <summary>
    /// Грубая оценка числа токенов: символы / 4.
    ///
    /// Это ПРИБЛИЗИТЕЛЬНАЯ эвристика, а не токенизатор. Для русского текста реальное
    /// число токенов обычно выше оценки, для английского — близко к ней. Точную цифру
    /// знает только модель, но нам она и не нужна: задача — отсечь заведомо длинный
    /// текст до платного вызова, а не посчитать биллинг.
    /// </summary>
    public static int EstimateTokens(string text) => (text.Length + 3) / 4;

    /// <summary>
    /// Первая модель с IsAvailable == true (или null, если таких нет).
    ///
    /// Модель НЕ хардкодится сознательно: состав доступных моделей меняется на стороне
    /// сервера, и пример, зашивший конкретный id, однажды тихо сломается.
    /// </summary>
    public static AiModelInfo? PickModel(IReadOnlyList<AiModelInfo> models)
        => models.FirstOrDefault(model => model.IsAvailable);

    public static async Task<int> RunAsync(AiChatClient client, string text, bool isSandbox)
    {
        var models = await client.ListModelsAsync();

        Console.WriteLine("Доступные модели (GET /api/AiChat/models):");
        foreach (var model in models)
        {
            var mark = model.IsAvailable ? "[*]" : "[ ]";
            var state = model.IsAvailable ? "доступна" : "недоступна";
            Console.WriteLine($"  {mark} {model.Id,-9} {model.Name} — вход до {model.MaxInputTokens} токенов, {state}");
        }

        var chosen = PickModel(models);
        if (chosen is null)
        {
            Console.Error.WriteLine("\nНи одна модель сейчас не доступна (isAvailable=false у всех). "
                                    + "Отправлять нечего — выходим.");
            return 1;
        }

        Console.WriteLine($"\nВыбрана: {chosen.Id} ({chosen.Name}) — первая доступная в списке.");
        Console.WriteLine($"  Лимит входа: {chosen.MaxInputTokens} токенов");

        // Цена запроса складывается из двух частей: ставки за сам запрос (одинаковой для всех
        // моделей) и стоимости токенов ВЫБРАННОЙ модели. Вторую часть модель объявляет сама —
        // полями inputPricePer1kTokens / outputPricePer1kTokens (кредитов за 1000 токенов).
        // Их стоит показать пользователю ДО отправки: у разных моделей она отличается в разы.
        if (chosen.InputPricePer1kTokens > 0 || chosen.OutputPricePer1kTokens > 0)
        {
            Console.WriteLine("  Токены этой модели тарифицируются отдельно — см. https://atlorium.com/pricing");
        }

        if (chosen.FreeQuotaEligible)
        {
            Console.WriteLine("  Дневная квота тарифа покрывает запросы к этой модели");
        }

        var prompt = "Сделай краткую выжимку текста ниже: 2–3 предложения, только суть, "
                     + $"без вступлений и повторов.\n\nТекст:\n{text}";

        // ── Проверка длины ДО отправки. /send — платный вызов, и слать в него заведомо
        //    неподъёмный текст значит потратить деньги и квоту на заведомо провальном запросе.
        //    Единственный лимит длины сообщения — токенный и зависит от модели: сервер сверяет
        //    ввод с maxInputTokens той модели, которую вы указали в запросе.
        var tokens = EstimateTokens(prompt);
        Console.WriteLine("\nПроверка длины до отправки:");
        Console.WriteLine($"  Символов в запросе: {prompt.Length}");
        Console.WriteLine($"  Оценка токенов: ~{tokens} (приблизительно: символы / 4)");

        if (tokens > chosen.MaxInputTokens)
        {
            Console.Error.WriteLine($"\nТекст длиннее лимита модели {chosen.Id} "
                                    + $"(~{tokens} > {chosen.MaxInputTokens} токенов). Сократите его или разбейте "
                                    + "на части — запрос не отправлен, деньги не потрачены.");
            return 1;
        }

        Console.WriteLine("  Влезает — отправляем.");

        // ── Единственный платный вызов за весь прогон.
        var answer = await client.SendAsync(prompt, chosen.Id);

        Console.WriteLine("\n── Выжимка ───────────────────────────────────────────────────────");
        Console.WriteLine(answer.Reply);
        Console.WriteLine("──────────────────────────────────────────────────────────────────");
        Console.WriteLine($"\nМодель: {answer.Model}");
        Console.WriteLine($"Токены: prompt {answer.PromptTokens} + completion {answer.CompletionTokens} "
                          + $"= {answer.TotalTokens}");
        Console.WriteLine($"Сессия: {answer.SessionId}");

        // ── Работа с сессией. Передав этот sessionId в следующий /send, диалог можно
        //    продолжить: «а теперь короче», «переведи на английский» — модель увидит контекст.
        var session = await client.GetSessionAsync(answer.SessionId);
        if (session is null)
        {
            Console.WriteLine("\nGET /api/AiChat/session/{id} → 404: в песочнице история сессии не сохраняется.");
            Console.WriteLine("С боевым ключом сессия живёт час и доступна по этому же запросу — а sessionId");
            Console.WriteLine("можно передать в следующий /send и продолжить диалог.");
        }
        else
        {
            Console.WriteLine($"\nСессия: сообщений {session.MessageCount}, создана {session.CreatedAt:O}, "
                              + $"последняя активность {session.LastActivityAt:O}.");
        }

        if (isSandbox)
        {
            Console.WriteLine("\nНапоминание: reply выше — заглушка песочницы, а не работа модели. Боевой ключ");
            Console.WriteLine("вернёт настоящую выжимку тем же кодом.");
        }

        return 0;
    }
}
