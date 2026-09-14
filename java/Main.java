/*
 * Клиент API ИИ-чата Atlorium — суммаризация текста нейросетью через HTTP.
 *
 * Запуск (работает сразу, без регистрации — на демо-ключе).
 * Начиная с Java 11 файл запускается напрямую, без компиляции и без зависимостей:
 *
 *     java Main.java
 *     java Main.java "свой текст для суммаризации"
 *
 * Боевой ключ: получить на https://atlorium.com и положить в переменную окружения
 * ATLORIUM_API_KEY. Код при этом не меняется.
 */

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Main {

    /**
     * Публичный демо-ключ. С ним /send возвращает МОК: реальная ИИ-модель не вызывается,
     * токены не тратятся, а в поле reply лежит заглушка, которая честно об этом сообщает.
     * Механику (выбор модели, сессии, учёт токенов) на нём отладить можно, саму
     * суммаризацию — нет. Для настоящих ответов модели нужен боевой ключ.
     */
    static final String SANDBOX_KEY = "ak_sandbox_demo_mockdata_v1";

    static final String API_KEY = envOr("ATLORIUM_API_KEY", SANDBOX_KEY);
    static final String BASE_URL = envOr("ATLORIUM_BASE_URL", "https://atlorium.com");

    /**
     * Повтор после 429 — ровно один раз. Лимит у ИИ-чата самый жёсткий в Atlorium,
     * поэтому повторять запросы подряд бессмысленно.
     */
    static final int MAX_RETRIES = 1;

    /**
     * Потолок ожидания. Исчерпав ЧАСОВОЙ лимит, сервер честно просит подождать 40+ минут.
     * Спать столько нельзя — процесс (и CI-джоб) просто зависнет. Дольше потолка не ждём,
     * а честно сообщаем «квота исчерпана» и выходим.
     */
    static final int MAX_RETRY_DELAY_SEC = 120;

    static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(60))
            .build();

    static String envOr(String key, String fallback) {
        String value = System.getenv(key);
        return (value == null || value.isBlank()) ? fallback : value;
    }

    /** Ошибка API: HTTP-код разложен в человекочитаемую причину. */
    static class AtloriumException extends RuntimeException {
        private static final Map<Integer, String> REASONS = new HashMap<>();

        static {
            REASONS.put(400, "Сообщение пустое, длиннее лимита выбранной модели или указана неизвестная модель");
            REASONS.put(401, "API-ключ отсутствует, просрочен или недействителен");
            REASONS.put(402, "Недостаточно кредитов: перед вызовом модели удерживается максимально возможная "
                    + "стоимость запроса — пополните на https://atlorium.com");
            REASONS.put(404, "Сессия не найдена или истекла");
            REASONS.put(429, "Превышен лимит запросов — повторите позже");
            REASONS.put(500, "Ошибка ИИ-сервиса при обработке запроса (в том числе исчерпан лимит сообщений в сессии)");
            REASONS.put(503, "Выбранная модель временно недоступна — возьмите другую из GET /api/AiChat/models "
                    + "(за сбой на своей стороне мы не списываем деньги)");
        }

        final int status;

        AtloriumException(int status, String body) {
            super("HTTP " + status + ": "
                    + REASONS.getOrDefault(status, "Неизвестная ошибка")
                    + ". Ответ сервера: " + body.substring(0, Math.min(200, body.length())));
            this.status = status;
        }
    }

    // ── HTTP ─────────────────────────────────────────────────────────────────

    static HttpResponse<String> request(String method, String path, String jsonBody)
            throws IOException, InterruptedException {

        HttpRequest.BodyPublisher body = jsonBody == null
                ? HttpRequest.BodyPublishers.noBody()
                // Явный UTF-8: сообщения на русском, и дефолтная кодировка платформы
                // на Windows превратила бы их в мусор.
                : HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8);

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(BASE_URL + path))
                .header("Authorization", "Bearer " + API_KEY)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(60))
                .method(method, body);

        if (jsonBody != null) {
            builder.header("Content-Type", "application/json; charset=utf-8");
        }

        return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /**
     * Сколько ждать после 429. Мусор и слишком большие значения не берём на веру:
     * ноль означал бы busy-loop, 40+ минут — зависший процесс. Ноль на выходе
     * значит «ждать бессмысленно долго, сдавайся».
     */
    static int retryAfter(HttpResponse<String> response) {
        int seconds;
        try {
            seconds = Integer.parseInt(response.headers().firstValue("Retry-After").orElse(""));
        } catch (NumberFormatException error) {
            seconds = 20;
        }
        if (seconds <= 0) {
            seconds = 20;
        }
        return seconds <= MAX_RETRY_DELAY_SEC ? seconds : 0;
    }

    // ── Разбор и сборка JSON ─────────────────────────────────────────────────
    // Пример намеренно оставлен без внешних зависимостей, чтобы запускаться одной
    // командой `java Main.java`. В рабочем проекте берите Jackson или Gson — эти
    // регулярки существуют только ради отсутствия pom.xml.
    //
    // Внимание: reply приходит с русским текстом, кавычками-ёлочками и переводами
    // строк (\n внутри JSON-строки). Поэтому строковый шаблон учитывает экранирование
    // ((?:[^"\\]|\\.)*), а прочитанное значение прогоняется через unescape().

    static String str(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        return matcher.find() ? unescape(matcher.group(1)) : null;
    }

    static Integer number(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\"\\s*:\\s*(-?\\d+)").matcher(json);
        return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
    }

    // Цены токенов приходят дробными (кредитов за 1000 токенов), поэтому для них
    // нужен отдельный шаблон: number() выше берёт только целую часть и на "0.15"
    // молча вернул бы 0. Поля нет в ответе — считаем, что токены не тарифицируются.
    static double decimal(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)").matcher(json);
        return matcher.find() ? Double.parseDouble(matcher.group(1)) : 0.0;
    }

    static boolean bool(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\"\\s*:\\s*(true|false)").matcher(json);
        return matcher.find() && "true".equals(matcher.group(1));
    }

    // Разворачивает JSON-экранирование: кавычку, слэш, перевод строки, табуляцию и
    // шестнадцатеричные escape-последовательности вида "u" + 4 цифры.
    // (Сама последовательность здесь не написана буквально: javac разбирает такие
    //  escape'ы даже внутри комментариев и ругается на «illegal unicode escape».)
    static String unescape(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char symbol = value.charAt(i);
            if (symbol != '\\' || i + 1 >= value.length()) {
                out.append(symbol);
                continue;
            }
            char next = value.charAt(++i);
            switch (next) {
                case 'n':
                    out.append('\n');
                    break;
                case 'r':
                    out.append('\r');
                    break;
                case 't':
                    out.append('\t');
                    break;
                case 'b':
                    out.append('\b');
                    break;
                case 'f':
                    out.append('\f');
                    break;
                case 'u':
                    out.append((char) Integer.parseInt(value.substring(i + 1, i + 5), 16));
                    i += 4;
                    break;
                default:
                    // Кавычка, обратный и прямой слэш — сами себя.
                    out.append(next);
            }
        }
        return out.toString();
    }

    /**
     * Экранирует строку для вставки в JSON-тело запроса.
     *
     * Без этого текст с кавычкой или переводом строки (а суммаризируемый текст —
     * это ровно то, где они бывают) сломал бы тело запроса и дал бы 400.
     */
    static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char symbol = value.charAt(i);
            switch (symbol) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (symbol < 0x20) {
                        out.append(String.format("\\u%04x", (int) symbol));
                    } else {
                        // Кириллицу и «ёлочки» отправляем как есть: тело уходит в UTF-8.
                        out.append(symbol);
                    }
            }
        }
        return out.toString();
    }

    /** Режет JSON-массив на объекты верхнего уровня. В моделях вложенных объектов нет. */
    static List<String> objects(String jsonArray) {
        List<String> items = new ArrayList<>();
        Matcher matcher = Pattern.compile("\\{[^{}]*\\}").matcher(jsonArray);
        while (matcher.find()) {
            items.add(matcher.group());
        }
        return items;
    }

    // ── Клиент ───────────────────────────────────────────────────────────────

    /**
     * GET /api/AiChat/models — список моделей сервера.
     *
     * Ключ нужен (без него 401), но квоту платного /send этот вызов не расходует:
     * в ответе нет даже заголовка X-Atlorium-Sandbox.
     */
    static List<String> listModels() throws IOException, InterruptedException {
        HttpResponse<String> response = request("GET", "/api/AiChat/models", null);
        if (response.statusCode() != 200) {
            throw new AtloriumException(response.statusCode(), response.body());
        }
        return objects(response.body());
    }

    /**
     * POST /api/AiChat/send — единственный платный вызов сервиса.
     *
     * sessionId = null создаёт новую сессию; передав sessionId из прошлого ответа,
     * можно продолжить диалог — модель увидит предыдущие сообщения.
     */
    static String send(String message, String model, String sessionId)
            throws IOException, InterruptedException {

        String body = "{\"message\":\"" + escape(message) + "\","
                + "\"sessionId\":" + (sessionId == null ? "null" : "\"" + escape(sessionId) + "\"") + ","
                + "\"model\":" + (model == null ? "null" : "\"" + escape(model) + "\"") + "}";

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            HttpResponse<String> response = request("POST", "/api/AiChat/send", body);

            // 429 — не поломка, а реальный лимит продукта.
            if (response.statusCode() == 429 && attempt < MAX_RETRIES) {
                int delay = retryAfter(response);
                if (delay == 0) {
                    throw new AtloriumException(429, "лимит запросов исчерпан, повторите позже");
                }
                System.err.println("  ... лимит запросов, пауза " + delay + " с");
                Thread.sleep(delay * 1000L);
                continue;
            }

            if (response.statusCode() != 200) {
                throw new AtloriumException(response.statusCode(), response.body());
            }
            return response.body();
        }

        throw new AtloriumException(429, "лимит запросов исчерпан, повторите позже");
    }

    /**
     * GET /api/AiChat/session/{id} — сколько сообщений в сессии и когда была активна.
     *
     * null означает 404. В песочнице это штатный случай: мок не сохраняет историю,
     * поэтому 404 приходит сразу после /send.
     */
    static String getSession(String sessionId) throws IOException, InterruptedException {
        String path = "/api/AiChat/session/" + URLEncoder.encode(sessionId, StandardCharsets.UTF_8);
        HttpResponse<String> response = request("GET", path, null);

        if (response.statusCode() == 404) {
            return null;
        }
        if (response.statusCode() != 200) {
            throw new AtloriumException(response.statusCode(), response.body());
        }
        return response.body();
    }

    /** DELETE /api/AiChat/session/{id} — стереть историю. false, если сессии уже нет. */
    static boolean deleteSession(String sessionId) throws IOException, InterruptedException {
        String path = "/api/AiChat/session/" + URLEncoder.encode(sessionId, StandardCharsets.UTF_8);
        HttpResponse<String> response = request("DELETE", path, null);

        if (response.statusCode() == 404) {
            return false;
        }
        if (response.statusCode() != 200) {
            throw new AtloriumException(response.statusCode(), response.body());
        }
        return true;
    }

    // ── Применение: суммаризация текста ──────────────────────────────────────
    // Ценность не в том, чтобы дёрнуть /send, а в том, чтобы дёрнуть его ОДИН раз и
    // по делу. Две вещи, которые делает эта функция и не делает наивный пример:
    //   1) не хардкодит модель — берёт первую ДОСТУПНУЮ из /models;
    //   2) считает длину ДО отправки и не тратит платный вызов на заведомо провальный.

    /**
     * Грубая оценка числа токенов: символы / 4.
     *
     * Это ПРИБЛИЗИТЕЛЬНАЯ эвристика, а не токенизатор. Для русского текста реальное
     * число токенов обычно выше оценки, для английского — близко к ней. Точную цифру
     * знает только модель, но нам она и не нужна: задача — отсечь заведомо длинный
     * текст до платного вызова, а не посчитать биллинг.
     */
    static int estimateTokens(String text) {
        return (text.length() + 3) / 4;
    }

    /**
     * Первая модель с isAvailable == true (или null, если таких нет).
     *
     * Модель НЕ хардкодится сознательно: состав доступных моделей меняется на стороне
     * сервера, и пример, зашивший конкретный id, однажды тихо сломается.
     */
    static String pickModel(List<String> models) {
        for (String model : models) {
            if (bool(model, "isAvailable")) {
                return model;
            }
        }
        return null;
    }

    static int summarize(String text) throws IOException, InterruptedException {
        List<String> models = listModels();

        System.out.println("Доступные модели (GET /api/AiChat/models):");
        for (String model : models) {
            boolean available = bool(model, "isAvailable");
            System.out.printf("  %s %-9s %s — вход до %d токенов, %s%n",
                    available ? "[*]" : "[ ]",
                    str(model, "id"),
                    str(model, "name"),
                    number(model, "maxInputTokens"),
                    available ? "доступна" : "недоступна");
        }

        String chosen = pickModel(models);
        if (chosen == null) {
            System.err.println("\nНи одна модель сейчас не доступна (isAvailable=false у всех). "
                    + "Отправлять нечего — выходим.");
            return 1;
        }

        String modelId = str(chosen, "id");
        int maxInput = number(chosen, "maxInputTokens");

        System.out.printf("%nВыбрана: %s (%s) — первая доступная в списке.%n", modelId, str(chosen, "name"));
        System.out.printf("  Лимит входа: %d токенов%n", maxInput);

        // Цена запроса складывается из двух частей: ставки за сам запрос (одинаковой для всех
        // моделей) и стоимости токенов ВЫБРАННОЙ модели. Вторую часть модель объявляет сама —
        // полями inputPricePer1kTokens / outputPricePer1kTokens (кредитов за 1000 токенов).
        // Их стоит показать пользователю ДО отправки: у разных моделей она отличается в разы.
        if (decimal(chosen, "inputPricePer1kTokens") > 0 || decimal(chosen, "outputPricePer1kTokens") > 0) {
            System.out.println("  Токены этой модели тарифицируются отдельно — см. https://atlorium.com/pricing");
        }
        if (bool(chosen, "freeQuotaEligible")) {
            System.out.println("  Дневная квота тарифа покрывает запросы к этой модели");
        }

        String prompt = "Сделай краткую выжимку текста ниже: 2–3 предложения, только суть, "
                + "без вступлений и повторов.\n\nТекст:\n" + text;

        // ── Проверка длины ДО отправки. /send — платный вызов, и слать в него заведомо
        //    неподъёмный текст значит потратить деньги и квоту на заведомо провальном запросе.
        //    Единственный лимит длины сообщения — токенный и зависит от модели: сервер сверяет
        //    ввод с maxInputTokens той модели, которую вы указали в запросе.
        int tokens = estimateTokens(prompt);
        System.out.println("\nПроверка длины до отправки:");
        System.out.printf("  Символов в запросе: %d%n", prompt.length());
        System.out.printf("  Оценка токенов: ~%d (приблизительно: символы / 4)%n", tokens);

        if (tokens > maxInput) {
            System.err.printf("%nТекст длиннее лимита модели %s (~%d > %d токенов). Сократите его или "
                    + "разбейте на части — запрос не отправлен, деньги не потрачены.%n",
                    modelId, tokens, maxInput);
            return 1;
        }

        System.out.println("  Влезает — отправляем.");

        // ── Единственный платный вызов за весь прогон.
        String answer = send(prompt, modelId, null);
        String sessionId = str(answer, "sessionId");

        System.out.println("\n── Выжимка ───────────────────────────────────────────────────────");
        System.out.println(str(answer, "reply"));
        System.out.println("──────────────────────────────────────────────────────────────────");
        System.out.println("\nМодель: " + str(answer, "model"));
        System.out.printf("Токены: prompt %d + completion %d = %d%n",
                number(answer, "promptTokens"),
                number(answer, "completionTokens"),
                number(answer, "totalTokens"));
        System.out.println("Сессия: " + sessionId);

        // ── Работа с сессией. Передав этот sessionId в следующий /send, диалог можно
        //    продолжить: «а теперь короче», «переведи на английский» — модель увидит контекст.
        String session = getSession(sessionId);
        if (session == null) {
            System.out.println("\nGET /api/AiChat/session/{id} → 404: в песочнице история сессии не сохраняется.");
            System.out.println("С боевым ключом сессия живёт час и доступна по этому же запросу — а sessionId");
            System.out.println("можно передать в следующий /send и продолжить диалог.");
        } else {
            System.out.printf("%nСессия: сообщений %d, создана %s, последняя активность %s.%n",
                    number(session, "messageCount"),
                    str(session, "createdAt"),
                    str(session, "lastActivityAt"));
        }

        if (API_KEY.equals(SANDBOX_KEY)) {
            System.out.println("\nНапоминание: reply выше — заглушка песочницы, а не работа модели. Боевой ключ");
            System.out.println("вернёт настоящую выжимку тем же кодом.");
        }

        return 0;
    }

    static final String DEFAULT_TEXT =
            "Клиент пишет, что после обновления мобильного приложения до версии 4.2 перестали "
            + "приходить push-уведомления о новых заказах. Проблема воспроизводится только на "
            + "Android 14, на iOS всё работает штатно. Клиент уже переустановил приложение, "
            + "проверил разрешения в настройках системы и перезагрузил телефон — не помогло. "
            + "В логах нашего сервера видно, что уведомления отправляются и FCM возвращает "
            + "успешный статус доставки. Клиент просит решить вопрос срочно: из-за пропущенных "
            + "заказов он теряет выручку, вчера сорвалась доставка на крупную сумму. Он также "
            + "спрашивает про компенсацию и предупреждает, что уйдёт к конкурентам, если проблема "
            + "не будет решена до конца недели.";

    public static void main(String[] args) throws Exception {
        if (API_KEY.equals(SANDBOX_KEY)) {
            System.out.println("Демо-ключ: ответ модели — заглушка (мок). Реальная ИИ-модель не вызывается, "
                    + "токены не тратятся.\n");
        }

        String text = args.length > 0 ? args[0] : DEFAULT_TEXT;

        try {
            System.exit(summarize(text));
        } catch (AtloriumException error) {
            System.err.println("Ошибка: " + error.getMessage());
            System.exit(1);
        }
    }
}
