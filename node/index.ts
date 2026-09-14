/**
 * Клиент API ИИ-чата Atlorium — суммаризация текста нейросетью через HTTP.
 *
 * Запуск (работает сразу, без регистрации — на демо-ключе):
 *   npm install
 *   npm start
 *   npm start -- "свой текст для суммаризации"
 *
 * Боевой ключ: получить на https://atlorium.com и положить в переменную окружения
 * ATLORIUM_API_KEY. Код при этом не меняется.
 */

/**
 * Публичный демо-ключ. С ним /send возвращает МОК: реальная ИИ-модель не вызывается,
 * токены не тратятся, а в поле reply лежит заглушка, которая честно об этом сообщает.
 * Механику (выбор модели, сессии, учёт токенов) на нём отладить можно, саму
 * суммаризацию — нет. Для настоящих ответов модели нужен боевой ключ.
 */
const SANDBOX_KEY = 'ak_sandbox_demo_mockdata_v1';

const API_KEY = process.env.ATLORIUM_API_KEY ?? SANDBOX_KEY;
const BASE_URL = process.env.ATLORIUM_BASE_URL ?? 'https://atlorium.com';

const TIMEOUT_MS = 60_000;

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
const MAX_RETRY_DELAY_SEC = 120;

/** Модель из GET /api/AiChat/models. */
export interface AiModelInfo {
  id: string;
  name: string;
  description: string;
  contextWindowTokens: number;
  maxInputTokens: number;
  maxOutputTokens: number;
  maxTokens: number;
  /** Ключевое поле: сервер может отключить модель, и тогда /send с её id не пройдёт. */
  isAvailable: boolean;
  /** true — модель крутится на сервере Atlorium, переписка не уходит наружу. */
  isLocal: boolean;
  // Ценовые поля объявлены НЕОБЯЗАТЕЛЬНЫМИ: сервер мог их ещё не начать отдавать,
  // и клиент, который на них рассчитывает жёстко, сломался бы на пустом месте.
  // Отсутствует — считаем, что токены не тарифицируются (0 / false).
  /** Цена входных токенов модели, кредитов за 1000. 0 — токены не тарифицируются. */
  inputPricePer1kTokens?: number;
  /** Цена выходных токенов модели, кредитов за 1000. 0 — токены не тарифицируются. */
  outputPricePer1kTokens?: number;
  /** Покрывает ли запросы к этой модели дневная квота тарифа. */
  freeQuotaEligible?: boolean;
}

/** Ответ POST /api/AiChat/send. */
export interface AiChatResponse {
  sessionId: string;
  reply: string;
  model: string;
  promptTokens: number;
  completionTokens: number;
  totalTokens: number;
  createdAt: string;
}

/** Ответ GET /api/AiChat/session/{id}. */
export interface AiChatSession {
  sessionId: string;
  messageCount: number;
  createdAt: string;
  lastActivityAt: string;
}

const ERROR_REASONS: Record<number, string> = {
  400: 'Сообщение пустое, длиннее лимита выбранной модели или указана неизвестная модель',
  401: 'API-ключ отсутствует, просрочен или недействителен',
  402: 'Недостаточно кредитов: перед вызовом модели удерживается максимально возможная '
    + 'стоимость запроса — пополните на https://atlorium.com',
  404: 'Сессия не найдена или истекла',
  429: 'Превышен лимит запросов — повторите позже',
  500: 'Ошибка ИИ-сервиса при обработке запроса (в том числе исчерпан лимит сообщений в сессии)',
  503: 'Выбранная модель временно недоступна — возьмите другую из GET /api/AiChat/models '
    + '(за сбой на своей стороне мы не списываем деньги)',
};

/** Ошибка API: HTTP-код разложен в человекочитаемую причину. */
export class AtloriumError extends Error {
  constructor(readonly status: number, body: string) {
    const reason = ERROR_REASONS[status] ?? 'Неизвестная ошибка';
    super(`HTTP ${status}: ${reason}. Ответ сервера: ${body.slice(0, 200)}`);
    this.name = 'AtloriumError';
  }
}

function headers(jsonBody = false): Record<string, string> {
  const result: Record<string, string> = {
    Authorization: `Bearer ${API_KEY}`,
    Accept: 'application/json',
  };
  // Сообщения на русском — тело обязано уехать в UTF-8.
  if (jsonBody) result['Content-Type'] = 'application/json; charset=utf-8';
  return result;
}

/**
 * Сколько ждать после 429. Мусор и слишком большие значения не берём на веру.
 *
 * Ноль (или мусор) означал бы «повторяй немедленно» — клиент ушёл бы в busy-loop.
 * Значение в 40+ минут (так сервер отвечает на исчерпанный часовой лимит) означало
 * бы «спи почти час». Возвращаем 0, если ждать бессмысленно долго: вызывающий сдастся.
 */
function retryAfter(response: Response): number {
  const parsed = Number.parseInt(response.headers.get('Retry-After') ?? '', 10);
  const seconds = Number.isFinite(parsed) && parsed > 0 ? parsed : 20;
  return seconds <= MAX_RETRY_DELAY_SEC ? seconds : 0;
}

const sleep = (seconds: number): Promise<void> =>
  new Promise((resolve) => setTimeout(resolve, seconds * 1000));

// ── Клиент ────────────────────────────────────────────────────────────────────

/**
 * GET /api/AiChat/models — список моделей сервера.
 *
 * Ключ нужен (без него 401), но квоту платного /send этот вызов не расходует:
 * в ответе нет даже заголовка X-Atlorium-Sandbox.
 */
export async function listModels(): Promise<AiModelInfo[]> {
  const response = await fetch(`${BASE_URL}/api/AiChat/models`, {
    headers: headers(),
    signal: AbortSignal.timeout(TIMEOUT_MS),
  });
  if (!response.ok) throw new AtloriumError(response.status, await response.text());
  return (await response.json()) as AiModelInfo[];
}

/**
 * POST /api/AiChat/send — единственный платный вызов сервиса.
 *
 * sessionId = null создаёт новую сессию; передав sessionId из прошлого ответа,
 * можно продолжить диалог — модель увидит предыдущие сообщения.
 */
export async function send(
  message: string,
  model: string | null = null,
  sessionId: string | null = null,
): Promise<AiChatResponse> {
  const payload = JSON.stringify({ message, sessionId, model });

  for (let attempt = 0; attempt <= MAX_RETRIES; attempt += 1) {
    const response = await fetch(`${BASE_URL}/api/AiChat/send`, {
      method: 'POST',
      headers: headers(true),
      body: payload,
      signal: AbortSignal.timeout(TIMEOUT_MS),
    });

    // 429 — не поломка, а реальный лимит продукта.
    if (response.status === 429 && attempt < MAX_RETRIES) {
      const delay = retryAfter(response);
      if (delay === 0) throw new AtloriumError(429, 'лимит запросов исчерпан, повторите позже');
      console.error(`  ... лимит запросов, пауза ${delay} с`);
      await sleep(delay);
      continue;
    }

    if (!response.ok) throw new AtloriumError(response.status, await response.text());
    return (await response.json()) as AiChatResponse;
  }

  throw new AtloriumError(429, 'лимит запросов исчерпан, повторите позже');
}

/**
 * GET /api/AiChat/session/{id} — сколько сообщений в сессии и когда была активна.
 *
 * Возвращает null, если сессии нет (404). В песочнице это штатный случай:
 * мок не сохраняет историю, поэтому 404 приходит сразу после /send.
 */
export async function getSession(sessionId: string): Promise<AiChatSession | null> {
  const response = await fetch(
    `${BASE_URL}/api/AiChat/session/${encodeURIComponent(sessionId)}`,
    { headers: headers(), signal: AbortSignal.timeout(TIMEOUT_MS) },
  );
  if (response.status === 404) return null;
  if (!response.ok) throw new AtloriumError(response.status, await response.text());
  return (await response.json()) as AiChatSession;
}

/** DELETE /api/AiChat/session/{id} — стереть историю. false, если сессии уже нет. */
export async function deleteSession(sessionId: string): Promise<boolean> {
  const response = await fetch(
    `${BASE_URL}/api/AiChat/session/${encodeURIComponent(sessionId)}`,
    { method: 'DELETE', headers: headers(), signal: AbortSignal.timeout(TIMEOUT_MS) },
  );
  if (response.status === 404) return false;
  if (!response.ok) throw new AtloriumError(response.status, await response.text());
  return true;
}

// ── Применение: суммаризация текста ───────────────────────────────────────────
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
export function estimateTokens(text: string): number {
  return Math.ceil(text.length / 4);
}

/**
 * Первая модель с isAvailable === true.
 *
 * Модель НЕ хардкодится сознательно: состав доступных моделей меняется на стороне
 * сервера, и пример, зашивший конкретный id, однажды тихо сломается.
 */
export function pickModel(models: AiModelInfo[]): AiModelInfo | null {
  return models.find((model) => model.isAvailable) ?? null;
}

export async function summarize(text: string): Promise<number> {
  const models = await listModels();

  console.log('Доступные модели (GET /api/AiChat/models):');
  for (const model of models) {
    const mark = model.isAvailable ? '[*]' : '[ ]';
    const state = model.isAvailable ? 'доступна' : 'недоступна';
    console.log(
      `  ${mark} ${model.id.padEnd(9)} ${model.name} — вход до ${model.maxInputTokens} токенов, ${state}`,
    );
  }

  const chosen = pickModel(models);
  if (chosen === null) {
    console.error(
      '\nНи одна модель сейчас не доступна (isAvailable=false у всех). Отправлять нечего — выходим.',
    );
    return 1;
  }

  console.log(`\nВыбрана: ${chosen.id} (${chosen.name}) — первая доступная в списке.`);
  console.log(`  Лимит входа: ${chosen.maxInputTokens} токенов`);

  // Цена запроса складывается из двух частей: ставки за сам запрос (одинаковой для всех
  // моделей) и стоимости токенов ВЫБРАННОЙ модели. Вторую часть модель объявляет сама —
  // полями inputPricePer1kTokens / outputPricePer1kTokens (кредитов за 1000 токенов).
  // Их стоит показать пользователю ДО отправки: у разных моделей она отличается в разы.
  if ((chosen.inputPricePer1kTokens ?? 0) > 0 || (chosen.outputPricePer1kTokens ?? 0) > 0) {
    console.log('  Токены этой модели тарифицируются отдельно — см. https://atlorium.com/pricing');
  }
  if (chosen.freeQuotaEligible) {
    console.log('  Дневная квота тарифа покрывает запросы к этой модели');
  }

  const prompt =
    'Сделай краткую выжимку текста ниже: 2–3 предложения, только суть, ' +
    `без вступлений и повторов.\n\nТекст:\n${text}`;

  // ── Проверка длины ДО отправки. /send — платный вызов, и слать в него заведомо
  //    неподъёмный текст значит потратить деньги и квоту на заведомо провальном запросе.
  //    Единственный лимит длины сообщения — токенный и зависит от модели: сервер сверяет
  //    ввод с maxInputTokens той модели, которую вы указали в запросе.
  const tokens = estimateTokens(prompt);
  console.log('\nПроверка длины до отправки:');
  console.log(`  Символов в запросе: ${prompt.length}`);
  console.log(`  Оценка токенов: ~${tokens} (приблизительно: символы / 4)`);

  if (tokens > chosen.maxInputTokens) {
    console.error(
      `\nТекст длиннее лимита модели ${chosen.id} (~${tokens} > ${chosen.maxInputTokens} токенов). ` +
        'Сократите его или разбейте на части — запрос не отправлен, деньги не потрачены.',
    );
    return 1;
  }

  console.log('  Влезает — отправляем.');

  // ── Единственный платный вызов за весь прогон.
  const answer = await send(prompt, chosen.id, null);

  console.log('\n── Выжимка ───────────────────────────────────────────────────────');
  console.log(answer.reply);
  console.log('──────────────────────────────────────────────────────────────────');
  console.log(`\nМодель: ${answer.model}`);
  console.log(
    `Токены: prompt ${answer.promptTokens} + completion ${answer.completionTokens} = ${answer.totalTokens}`,
  );
  console.log(`Сессия: ${answer.sessionId}`);

  // ── Работа с сессией. Передав этот sessionId в следующий /send, диалог можно
  //    продолжить: «а теперь короче», «переведи на английский» — модель увидит контекст.
  const session = await getSession(answer.sessionId);
  if (session === null) {
    console.log(
      '\nGET /api/AiChat/session/{id} → 404: в песочнице история сессии не сохраняется.\n' +
        'С боевым ключом сессия живёт час и доступна по этому же запросу — а sessionId\n' +
        'можно передать в следующий /send и продолжить диалог.',
    );
  } else {
    console.log(
      `\nСессия: сообщений ${session.messageCount}, создана ${session.createdAt}, ` +
        `последняя активность ${session.lastActivityAt}.`,
    );
  }

  if (API_KEY === SANDBOX_KEY) {
    console.log(
      '\nНапоминание: reply выше — заглушка песочницы, а не работа модели. Боевой ключ\n' +
        'вернёт настоящую выжимку тем же кодом.',
    );
  }

  return 0;
}

const DEFAULT_TEXT =
  'Клиент пишет, что после обновления мобильного приложения до версии 4.2 перестали ' +
  'приходить push-уведомления о новых заказах. Проблема воспроизводится только на ' +
  'Android 14, на iOS всё работает штатно. Клиент уже переустановил приложение, ' +
  'проверил разрешения в настройках системы и перезагрузил телефон — не помогло. ' +
  'В логах нашего сервера видно, что уведомления отправляются и FCM возвращает ' +
  'успешный статус доставки. Клиент просит решить вопрос срочно: из-за пропущенных ' +
  'заказов он теряет выручку, вчера сорвалась доставка на крупную сумму. Он также ' +
  'спрашивает про компенсацию и предупреждает, что уйдёт к конкурентам, если проблема ' +
  'не будет решена до конца недели.';

async function main(): Promise<void> {
  if (API_KEY === SANDBOX_KEY) {
    console.log(
      'Демо-ключ: ответ модели — заглушка (мок). Реальная ИИ-модель не вызывается, токены не тратятся.\n',
    );
  }

  const text = process.argv[2] ?? DEFAULT_TEXT;
  process.exitCode = await summarize(text);
}

// Запуск только когда файл выполняется напрямую, а не импортируется.
if (process.argv[1]?.includes('index')) {
  main().catch((error: unknown) => {
    console.error('Ошибка:', error instanceof Error ? error.message : error);
    process.exit(1);
  });
}
