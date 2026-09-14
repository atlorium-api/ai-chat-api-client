"""
Клиент API ИИ-чата Atlorium — суммаризация текста нейросетью через HTTP.

Запуск (работает сразу, без регистрации — на демо-ключе):
    pip install -r requirements.txt
    python main.py
    python main.py "свой текст для суммаризации"

Боевой ключ: получить на https://atlorium.com и положить в переменную окружения
ATLORIUM_API_KEY. Код при этом не меняется.
"""

import json
import math
import os
import sys
import time

import requests

# Публичный демо-ключ. С ним /send возвращает МОК: реальная ИИ-модель не вызывается,
# токены не тратятся, а в поле reply лежит заглушка, которая честно об этом сообщает.
# Механику (выбор модели, сессии, учёт токенов) на нём отладить можно, саму
# суммаризацию — нет. Для настоящих ответов модели нужен боевой ключ.
SANDBOX_KEY = "ak_sandbox_demo_mockdata_v1"

API_KEY = os.environ.get("ATLORIUM_API_KEY", SANDBOX_KEY)
BASE_URL = os.environ.get("ATLORIUM_BASE_URL", "https://atlorium.com")

TIMEOUT = 60

# Повтор после 429 — ровно один раз. Лимит у ИИ-чата самый жёсткий в Atlorium,
# поэтому повторять запросы подряд бессмысленно.
MAX_RETRIES = 1

# Потолок ожидания. Исчерпав ЧАСОВОЙ лимит, сервер честно просит подождать 40+ минут.
# Спать столько нельзя — процесс (и CI-джоб) просто зависнет. Дольше потолка не ждём,
# а честно сообщаем «квота исчерпана» и выходим.
MAX_RETRY_DELAY = 120


class AtloriumError(RuntimeError):
    """Ошибка API. Код HTTP разложен в человекочитаемую причину."""

    REASONS = {
        400: "Сообщение пустое, длиннее лимита выбранной модели или указана неизвестная модель",
        401: "API-ключ отсутствует, просрочен или недействителен",
        402: "Недостаточно кредитов: перед вызовом модели удерживается максимально возможная "
             "стоимость запроса — пополните на https://atlorium.com",
        404: "Сессия не найдена или истекла",
        429: "Превышен лимит запросов — повторите позже",
        500: "Ошибка ИИ-сервиса при обработке запроса (в том числе исчерпан лимит сообщений в сессии)",
        503: "Выбранная модель временно недоступна — возьмите другую из GET /api/AiChat/models "
             "(за сбой на своей стороне мы не списываем деньги)",
    }

    def __init__(self, status: int, body: str):
        reason = self.REASONS.get(status, "Неизвестная ошибка")
        super().__init__(f"HTTP {status}: {reason}. Ответ сервера: {body[:200]}")
        self.status = status


def _headers(json_body: bool = False) -> dict[str, str]:
    headers = {"Authorization": f"Bearer {API_KEY}", "Accept": "application/json"}
    if json_body:
        # Сообщения на русском — тело обязано уехать в UTF-8.
        headers["Content-Type"] = "application/json; charset=utf-8"
    return headers


def retry_after(response: requests.Response) -> int:
    """Сколько ждать после 429. Мусор и слишком большие значения не берём на веру.

    Ноль (или мусор) означал бы «повторяй немедленно» — клиент ушёл бы в busy-loop.
    Значение в 40+ минут (так сервер отвечает на исчерпанный часовой лимит) означало
    бы «спи почти час». Возвращаем 0, если ждать бессмысленно долго: вызывающий сдастся.
    """
    try:
        seconds = int(response.headers.get("Retry-After", ""))
    except ValueError:
        seconds = 20
    if seconds <= 0:
        seconds = 20
    return seconds if seconds <= MAX_RETRY_DELAY else 0


# ── Клиент ────────────────────────────────────────────────────────────────────


def list_models() -> list[dict]:
    """GET /api/AiChat/models — список моделей сервера.

    Ключ нужен (без него 401), но квоту платного /send этот вызов не расходует:
    в ответе нет даже заголовка X-Atlorium-Sandbox.
    """
    response = requests.get(f"{BASE_URL}/api/AiChat/models", headers=_headers(), timeout=TIMEOUT)
    if not response.ok:
        raise AtloriumError(response.status_code, response.text)
    return response.json()


def send(message: str, model: str | None = None, session_id: str | None = None) -> dict:
    """POST /api/AiChat/send — единственный платный вызов сервиса.

    session_id=None создаёт новую сессию; передав sessionId из прошлого ответа,
    можно продолжить диалог — модель увидит предыдущие сообщения.
    """
    payload = {"message": message, "sessionId": session_id, "model": model}

    for attempt in range(MAX_RETRIES + 1):
        response = requests.post(
            f"{BASE_URL}/api/AiChat/send",
            headers=_headers(json_body=True),
            data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
            timeout=TIMEOUT,
        )

        # 429 — не поломка, а реальный лимит продукта.
        if response.status_code == 429 and attempt < MAX_RETRIES:
            delay = retry_after(response)
            if delay == 0:
                raise AtloriumError(429, "лимит запросов исчерпан, повторите позже")
            print(f"  ... лимит запросов, пауза {delay} с", file=sys.stderr)
            time.sleep(delay)
            continue

        if not response.ok:
            raise AtloriumError(response.status_code, response.text)
        return response.json()

    raise AtloriumError(429, "лимит запросов исчерпан, повторите позже")


def get_session(session_id: str) -> dict | None:
    """GET /api/AiChat/session/{id} — сколько сообщений в сессии и когда была активна.

    Возвращает None, если сессии нет (404). В песочнице это штатный случай:
    мок не сохраняет историю, поэтому 404 приходит сразу после /send.
    """
    response = requests.get(
        f"{BASE_URL}/api/AiChat/session/{session_id}", headers=_headers(), timeout=TIMEOUT
    )
    if response.status_code == 404:
        return None
    if not response.ok:
        raise AtloriumError(response.status_code, response.text)
    return response.json()


def delete_session(session_id: str) -> bool:
    """DELETE /api/AiChat/session/{id} — стереть историю. False, если сессии уже нет."""
    response = requests.delete(
        f"{BASE_URL}/api/AiChat/session/{session_id}", headers=_headers(), timeout=TIMEOUT
    )
    if response.status_code == 404:
        return False
    if not response.ok:
        raise AtloriumError(response.status_code, response.text)
    return True


# ── Применение: суммаризация текста ───────────────────────────────────────────
# Ценность не в том, чтобы дёрнуть /send, а в том, чтобы дёрнуть его ОДИН раз и
# по делу. Две вещи, которые делает эта функция и не делает наивный пример:
#   1) не хардкодит модель — берёт первую ДОСТУПНУЮ из /models;
#   2) считает длину ДО отправки и не тратит платный вызов на заведомо провальный.


def estimate_tokens(text: str) -> int:
    """Грубая оценка числа токенов: символы / 4.

    Это ПРИБЛИЗИТЕЛЬНАЯ эвристика, а не токенизатор. Для русского текста реальное
    число токенов обычно выше оценки, для английского — близко к ней. Точную цифру
    знает только модель, но нам она и не нужна: задача — отсечь заведомо длинный
    текст до платного вызова, а не посчитать биллинг.
    """
    return math.ceil(len(text) / 4)


def pick_model(models: list[dict]) -> dict | None:
    """Первая модель с isAvailable == true.

    Модель НЕ хардкодится сознательно: состав доступных моделей меняется на стороне
    сервера, и пример, зашивший конкретный id, однажды тихо сломается.
    """
    for model in models:
        if model.get("isAvailable"):
            return model
    return None


def summarize(text: str) -> int:
    models = list_models()

    print("Доступные модели (GET /api/AiChat/models):")
    for model in models:
        mark = "[*]" if model.get("isAvailable") else "[ ]"
        state = "доступна" if model.get("isAvailable") else "недоступна"
        print(f"  {mark} {model['id']:<9} {model['name']} — вход до "
              f"{model['maxInputTokens']} токенов, {state}")

    chosen = pick_model(models)
    if chosen is None:
        print("\nНи одна модель сейчас не доступна (isAvailable=false у всех). "
              "Отправлять нечего — выходим.", file=sys.stderr)
        return 1

    print(f"\nВыбрана: {chosen['id']} ({chosen['name']}) — первая доступная в списке.")
    print(f"  Лимит входа: {chosen['maxInputTokens']} токенов")

    # Цена запроса складывается из двух частей: ставки за сам запрос (одинаковой для всех
    # моделей) и стоимости токенов ВЫБРАННОЙ модели. Вторую часть модель объявляет сама —
    # полями inputPricePer1kTokens / outputPricePer1kTokens (кредитов за 1000 токенов).
    # Их стоит показать пользователю ДО отправки: у разных моделей она отличается в разы.
    if float(chosen.get("inputPricePer1kTokens", 0)) > 0 or float(chosen.get("outputPricePer1kTokens", 0)) > 0:
        print("  Токены этой модели тарифицируются отдельно — см. https://atlorium.com/pricing")
    if chosen.get("freeQuotaEligible"):
        print("  Дневная квота тарифа покрывает запросы к этой модели")

    prompt = (
        "Сделай краткую выжимку текста ниже: 2–3 предложения, только суть, "
        "без вступлений и повторов.\n\nТекст:\n" + text
    )

    # ── Проверка длины ДО отправки. /send — платный вызов, и слать в него заведомо
    #    неподъёмный текст значит потратить деньги и квоту на заведомо провальном запросе.
    #    Единственный лимит длины сообщения — токенный и зависит от модели: сервер сверяет
    #    ввод с maxInputTokens той модели, которую вы указали в запросе.
    tokens = estimate_tokens(prompt)
    print("\nПроверка длины до отправки:")
    print(f"  Символов в запросе: {len(prompt)}")
    print(f"  Оценка токенов: ~{tokens} (приблизительно: символы / 4)")

    if tokens > chosen["maxInputTokens"]:
        print(f"\nТекст длиннее лимита модели {chosen['id']} "
              f"(~{tokens} > {chosen['maxInputTokens']} токенов). Сократите его или разбейте "
              "на части — запрос не отправлен, деньги не потрачены.", file=sys.stderr)
        return 1

    print("  Влезает — отправляем.")

    # ── Единственный платный вызов за весь прогон.
    answer = send(prompt, model=chosen["id"], session_id=None)

    print("\n── Выжимка ───────────────────────────────────────────────────────")
    print(answer["reply"])
    print("──────────────────────────────────────────────────────────────────")
    print(f"\nМодель: {answer['model']}")
    print(f"Токены: prompt {answer['promptTokens']} + completion "
          f"{answer['completionTokens']} = {answer['totalTokens']}")
    print(f"Сессия: {answer['sessionId']}")

    # ── Работа с сессией. Передав этот sessionId в следующий /send, диалог можно
    #    продолжить: «а теперь короче», «переведи на английский» — модель увидит контекст.
    session = get_session(answer["sessionId"])
    if session is None:
        print("\nGET /api/AiChat/session/{id} → 404: в песочнице история сессии "
              "не сохраняется.\nС боевым ключом сессия живёт час и доступна по этому же "
              "запросу — а sessionId\nможно передать в следующий /send и продолжить диалог.")
    else:
        print(f"\nСессия: сообщений {session['messageCount']}, создана "
              f"{session['createdAt']}, последняя активность {session['lastActivityAt']}.")

    if API_KEY == SANDBOX_KEY:
        print("\nНапоминание: reply выше — заглушка песочницы, а не работа модели. "
              "Боевой ключ\nвернёт настоящую выжимку тем же кодом.")

    return 0


DEFAULT_TEXT = (
    "Клиент пишет, что после обновления мобильного приложения до версии 4.2 перестали "
    "приходить push-уведомления о новых заказах. Проблема воспроизводится только на "
    "Android 14, на iOS всё работает штатно. Клиент уже переустановил приложение, "
    "проверил разрешения в настройках системы и перезагрузил телефон — не помогло. "
    "В логах нашего сервера видно, что уведомления отправляются и FCM возвращает "
    "успешный статус доставки. Клиент просит решить вопрос срочно: из-за пропущенных "
    "заказов он теряет выручку, вчера сорвалась доставка на крупную сумму. Он также "
    "спрашивает про компенсацию и предупреждает, что уйдёт к конкурентам, если проблема "
    "не будет решена до конца недели."
)


def main() -> int:
    if API_KEY == SANDBOX_KEY:
        print("Демо-ключ: ответ модели — заглушка (мок). Реальная ИИ-модель не вызывается, "
              "токены не тратятся.\n")

    text = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_TEXT

    try:
        return summarize(text)
    except AtloriumError as error:
        print(f"Ошибка: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
