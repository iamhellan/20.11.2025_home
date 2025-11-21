package org.example;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitForSelectorState;
import org.junit.jupiter.api.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class v2_phone_registration {
    static Playwright playwright;
    static Browser browser;
    static BrowserContext context;
    static Page page;
    static TelegramNotifier tg;
    private static final Path MESSAGES_SESSION =
            Paths.get("resources", "sessions", "messages-session.json");

    // --- СЕЛЕКТОРЫ КРЕСТИКОВ / КНОПОК ЗАКРЫТИЯ ПОПАПОВ ---
    static final String[] POPUP_CLOSE_SELECTORS = new String[]{
            // арктик-модалки
            "div.box-modal_close.arcticmodal-close",
            ".arcticmodal-close",
            "div.box-modal_close",

            // overlay / фоновые кликабельные области
            "div.v--modal-background-click",
            ".v--modal-overlay",

            // окна регистрации / пост-регистрации
            "button.popup-registration__close",

            // идентификация / привязка / бонусы / переходы
            "button.identification-popup-close.identification-popup-binding__close",
            "button.identification-popup-close.identification-popup-get-bonus__close",
            "button.identification-popup-close.identification-popup-transition__close",

            // восстановление пароля
            "button.reset-password__close",

            // Vue UI
            "button.v--modal-close-btn",

            // общий случай
            "button[title='Закрыть']",
            ".popup__close",
            ".modal__close"
    };

    @BeforeAll
    static void setUpAll() {
        System.out.println("=== ИНИЦИАЛИЗАЦИЯ Playwright / браузера ===");
        playwright = Playwright.create();
        browser = playwright.chromium().launch(
                new BrowserType.LaunchOptions()
                        .setHeadless(false)
                        .setArgs(List.of("--start-maximized"))
        );
        context = browser.newContext(
                new Browser.NewContextOptions()
                        .setAcceptDownloads(true)
                        .setViewportSize(null)
        );
        page = context.newPage();
        page.setDefaultTimeout(30_000);
        page.setDefaultNavigationTimeout(60_000);

        String botToken = ConfigHelper.get("telegram.bot.token");
        String chatId = ConfigHelper.get("telegram.chat.id");
        tg = new TelegramNotifier(botToken, chatId);
        System.out.println("=== ИНИЦИАЛИЗАЦИЯ завершена ===");
    }

    @AfterAll
    static void tearDownAll() {
        System.out.println("=== ЗАКРЫТИЕ ресурсов ===");
        try { if (context != null) context.close(); } catch (Throwable ignored) {}
        try { if (browser != null) browser.close(); } catch (Throwable ignored) {}
        try { if (playwright != null) playwright.close(); } catch (Throwable ignored) {}
        System.out.println("Тест завершён ✅ (браузер и контекст закрыты)");
    }

    // ===== ХЕЛПЕРЫ =====
    static void pause(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) {} }
    static void pauseShort() { pause(150); }
    static void pauseMedium() { pause(350); }

    static void waitAndClick(Page page, String selector, int timeoutMs) {
        System.out.println("Ждём элемент и кликаем: " + selector);
        page.waitForSelector(selector,
                new Page.WaitForSelectorOptions().setTimeout(timeoutMs).setState(WaitForSelectorState.VISIBLE));
        page.locator(selector).first().click();
        pauseMedium();
    }

    static void clickIfVisible(Page page, String selector) {
        Locator loc = page.locator(selector);
        if (loc.count() > 0 && loc.first().isVisible()) {
            System.out.println("Элемент виден, кликаем: " + selector);
            loc.first().click(new Locator.ClickOptions().setTimeout(5000));
            pauseShort();
        } else {
            System.out.println("Элемент не найден или не виден, пропускаем: " + selector);
        }
    }

    private static void closeIdentificationPopups(Page page) {
        System.out.println("Пробуем закрыть всплывающие окна идентификации (если есть)");

        // Вариант 1: identification-popup-transition__close
        try {
            Locator close1 = page.locator("button.identification-popup-close.identification-popup-transition__close");
            close1.waitFor(new Locator.WaitForOptions().setTimeout(2000).setState(WaitForSelectorState.ATTACHED));
            if (close1.isVisible()) {
                close1.click();
                System.out.println("Закрыт popup (transition) ✅");
            }
        } catch (Exception e) {
            System.out.println("Popup (transition) не найден или уже закрыт");
        }

        // Вариант 2: identification-popup-binding__close
        try {
            Locator close2 = page.locator("button.identification-popup-close.identification-popup-binding__close");
            close2.waitFor(new Locator.WaitForOptions().setTimeout(2000).setState(WaitForSelectorState.ATTACHED));
            if (close2.isVisible()) {
                close2.click();
                System.out.println("Закрыт popup (binding) ✅");
            }
        } catch (Exception e) {
            System.out.println("Popup (binding) не найден или уже закрыт");
        }
    }

    private static final int CAPTCHA_APPEAR_TIMEOUT_MS = 15_000;   // ждём, появится ли капча
    private static final int CAPTCHA_SOLVE_TIMEOUT_MS  = 600_000;  // ждём, пока её решат (до 10 минут)

    // --- ЖДЁМ РЕШЕНИЯ КАПЧИ, НО ТОЛЬКО ЕСЛИ ОНА ВООБЩЕ ПОЯВИЛАСЬ ---
    static void waitUserSolvesCaptchaIfAppears(Page page) {
        System.out.println("Проверяю, появляется ли капча в течение " +
                (CAPTCHA_APPEAR_TIMEOUT_MS / 1000) + " секунд...");

        boolean captchaAppeared = false;
        try {
            Object result = page.waitForFunction(
                    "() => {" +
                            "  const iframes = Array.from(document.querySelectorAll('iframe'));" +
                            "  const hasCaptchaIframe = iframes.some(f => (f.src || '').toLowerCase().includes('captcha'));" +
                            "  const overlays = document.querySelectorAll('.g-recaptcha, .h-captcha, .captcha, .rc-anchor');" +
                            "  return hasCaptchaIframe || overlays.length > 0;" +
                            "}",
                    new Page.WaitForFunctionOptions()
                            .setTimeout(CAPTCHA_APPEAR_TIMEOUT_MS)
            ).jsonValue();

            captchaAppeared = Boolean.TRUE.equals(result);
        } catch (PlaywrightException e) {
            captchaAppeared = false;
        }

        if (!captchaAppeared) {
            System.out.println("Капча не появилась за " +
                    (CAPTCHA_APPEAR_TIMEOUT_MS / 1000) +
                    " секунд — считаем, что её нет и идём дальше без ожидания.");
            return;
        }

        System.out.println("Капча обнаружена — жду, пока ты её решишь (до 10 минут)...");

        try {
            page.waitForFunction(
                    "() => {" +
                            "  const iframes = Array.from(document.querySelectorAll('iframe'));" +
                            "  const hasCaptchaIframe = iframes.some(f => (f.src || '').toLowerCase().includes('captcha'));" +
                            "  const overlays = document.querySelectorAll('.g-recaptcha, .h-captcha, .captcha, .rc-anchor');" +
                            "  return !hasCaptchaIframe && overlays.length === 0;" +
                            "}",
                    new Page.WaitForFunctionOptions()
                            .setTimeout(CAPTCHA_SOLVE_TIMEOUT_MS)
            );
            System.out.println("Похоже, капча решена (оверлей исчез) ✅");
        } catch (PlaywrightException e) {
            throw new RuntimeException("Капча не была решена в отведённое время или селекторы капчи не подошли.", e);
        }
    }

    private static String fetchCodeFromGoogleMessages(Playwright playwright, Browser browser) {
        System.out.println("Открываем Google Messages с уже сохранённой сессией");

        BrowserContext messagesContext = browser.newContext(
                new Browser.NewContextOptions()
                        .setStorageStatePath(Paths.get("messages-session.json"))
        );

        Page messagesPage = messagesContext.newPage();
        messagesPage.navigate("https://messages.google.com/web/conversations");

        // 1. Открываем самый верхний (последний) чат
        Locator chat = messagesPage.locator("mws-conversation-list-item").first();
        chat.waitFor(new Locator.WaitForOptions().setTimeout(10_000));
        chat.click();
        messagesPage.waitForTimeout(1000);

        // 2. Берём последнее сообщение в этом чате
        Locator messageNodes = messagesPage.locator("div.text-msg.msg-content div.ng-star-inserted");
        int count = messageNodes.count();
        if (count == 0) {
            throw new RuntimeException("Не нашли ни одного сообщения в чате Google Messages");
        }

        String smsText = messageNodes.nth(count - 1).innerText();
        System.out.println("Текст последнего SMS: " + smsText);

        // 3. Вытаскиваем код (4–8 символов, буквы/цифры, первое слово)
        Pattern pattern = Pattern.compile("\\b([A-Za-z0-9]{4,8})\\b");
        Matcher matcher = pattern.matcher(smsText);
        if (matcher.find()) {
            String code = matcher.group(1);
            System.out.println("Извлечённый код подтверждения: " + code);
            messagesContext.close();
            return code;
        } else {
            messagesContext.close();
            throw new RuntimeException("Не удалось извлечь код подтверждения из текста SMS");
        }
    }

    static void jsClick(Locator loc) {
        if (loc.count() > 0) {
            System.out.println("Кликаем через JS по локатору: " + loc);
            loc.first().dispatchEvent("click");
        }
    }

    static void neutralizeOverlayIfNeeded(Page page) {
        System.out.println("Пробуем нейтрализовать оверлеи (если есть)...");
        page.evaluate("(() => {" +
                "const kill = sel => document.querySelectorAll(sel).forEach(n => {" +
                "  try { n.style.pointerEvents='none'; n.style.zIndex='0'; n.style.opacity='0.3'; } catch(e){} });" +
                "kill('.arcticmodal-container_i2');" +
                "kill('.arcticmodal-container_i');" +
                "kill('.v--modal-background-click');" +
                "kill('#modals-container *');" +
                "kill('.pf-main-container-wrapper-th-4 *');" +
                "kill('.js_reg_form_scroll.active_scroll');" +
                "})();");
    }

    // --- Закрыть все известные попапы ---
    static void closeAllKnownPopups(Page page, String contextLabel) {
        System.out.println("Пробуем закрыть всплывающие окна. Контекст: " + contextLabel);
        boolean closedSomething;

        // несколько проходов — закрытие одного окна может вызвать другое
        for (int round = 1; round <= 5; round++) {
            closedSomething = false;
            System.out.println("Раунд закрытия попапов #" + round);

            for (String sel : POPUP_CLOSE_SELECTORS) {
                Locator loc = page.locator(sel);
                if (loc.count() > 0 && loc.first().isVisible()) {
                    System.out.println("Найден попап-крестик: " + sel + " — пробуем кликнуть...");
                    try {
                        loc.first().click(new Locator.ClickOptions().setTimeout(3000));
                        closedSomething = true;
                        page.waitForTimeout(500);
                    } catch (Exception e) {
                        System.out.println("Не удалось кликнуть по " + sel + ": " + e.getMessage());
                        System.out.println("Пробуем закрыть через JS...");
                        try {
                            page.evaluate("document.querySelector('" + sel + "')?.click()");
                            closedSomething = true;
                            page.waitForTimeout(250);
                        } catch (Exception ignored) {
                        }
                    }
                }
            }

            if (!closedSomething) {
                System.out.println("Новых попапов не обнаружено, выходим из цикла закрытия.");
                break;
            }
        }

        System.out.println("Завершили попытки закрытия попапов. Контекст: " + contextLabel);
    }

    static void waitForRegistrationModal(Page page) {
        System.out.println("Ждём появление формы регистрации...");
        String[] sels = {
                "div#games_content.c-registration",
                "div.arcticmodal-container div.c-registration"
        };
        page.waitForSelector(String.join(", ", sels),
                new Page.WaitForSelectorOptions().setTimeout(30_000).setState(WaitForSelectorState.VISIBLE));
        System.out.println("Форма регистрации открыта ✅");
    }

    static String randomPromo(int len) {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        Random rnd = new Random();
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) sb.append(chars.charAt(rnd.nextInt(chars.length())));
        return sb.toString();
    }

    static Path ensureDownloadsDir() throws Exception {
        Path downloads = Paths.get("downloads");
        if (!Files.exists(downloads)) Files.createDirectories(downloads);
        return downloads;
    }

    // --- Google Messages (через messages-session.json) ---
    static String fetchSmsCodeFromGoogleMessages() {
        System.out.println("Открываем Google Messages с сохранённой сессией...");
        System.out.println("Использую storageState: " + MESSAGES_SESSION.toAbsolutePath());

        if (!Files.exists(MESSAGES_SESSION)) {
            throw new RuntimeException("Файл сессии не найден: " + MESSAGES_SESSION.toAbsolutePath());
        }

        BrowserContext messagesContext = browser.newContext(
                new Browser.NewContextOptions()
                        .setStorageStatePath(MESSAGES_SESSION)
        );

        Page messagesPage = messagesContext.newPage();
        messagesPage.setDefaultTimeout(20_000);
        messagesPage.navigate("https://messages.google.com/web/conversations");

        System.out.println("Ждём появление списка чатов в Google Messages...");
        for (int i = 0; i < 20; i++) {
            if (messagesPage.locator("mws-conversation-list-item").count() > 0) break;
            messagesPage.waitForTimeout(1000);
        }

        System.out.println("Открываем самый верхний чат (последний SMS)...");
        Locator chat = messagesPage.locator("mws-conversation-list-item").first();
        chat.click();
        messagesPage.waitForTimeout(1200);

        System.out.println("Читаем последнее сообщение и ищем код...");
        Locator nodes = messagesPage.locator("div.text-msg.msg-content div.ng-star-inserted");
        int count = nodes.count();
        String text = count > 0 ? nodes.nth(count - 1).innerText() : "";

        Matcher m = Pattern.compile("(?<!\\d)(\\d{4,8})(?!\\d)").matcher(text);
        String code = m.find() ? m.group(1) : null;

        messagesContext.close();

        if (code == null || code.isBlank()) {
            throw new RuntimeException("Код из SMS не найден (text: " + text + ")");
        }

        System.out.println("Код из SMS получен: " + code);
        return code;
    }

    static Map<String, String> extractCredentials(Page page) {
        System.out.println("Пробуем извлечь логин/пароль из текста страницы (резервный метод)...");
        String login = null, password = null;
        String txt = page.innerText("body");
        Matcher ml = Pattern.compile("Логин\\s*[:\\-]?\\s*(\\S+)", Pattern.CASE_INSENSITIVE).matcher(txt);
        if (ml.find()) login = ml.group(1);
        Matcher mp = Pattern.compile("Пароль\\s*[:\\-]?\\s*(\\S+)", Pattern.CASE_INSENSITIVE).matcher(txt);
        if (mp.find()) password = mp.group(1);
        Map<String, String> out = new HashMap<>();
        out.put("login", login);
        out.put("password", password);
        System.out.println("Извлечение завершено. Логин=" + login + ", Пароль=" + password);
        return out;
    }

    static void tryBindBySmsIfModalVisible(Page page) {
        System.out.println("Проверяем, открыто ли окно привязки по SMS...");
        Locator field = page.locator("input.phone-sms-modal-content__code").first();
        if (field == null || field.count() == 0 || !field.isVisible()) {
            System.out.println("Окно привязки по SMS не найдено, выходим из метода.");
            return;
        }
        System.out.println("Окно привязки по SMS найдено, вытаскиваем код из Google Messages...");
        String code = fetchSmsCodeFromGoogleMessages();
        field.fill(code);
        System.out.println("Код введён в поле, подтверждаем...");
        Locator confirmBtn = page.locator("button.phone-sms-modal-content__send:has-text('Подтвердить')");
        if (confirmBtn.count() > 0 && confirmBtn.first().isVisible()) {
            confirmBtn.first().click();
            System.out.println("Кнопка 'Подтвердить' нажата.");
            tg.sendMessage("🔐 Привязка по SMS подтверждена кодом: `" + code + "`");
        } else {
            System.out.println("Кнопка 'Подтвердить' не найдена.");
        }
    }

    @Test
    void v2_registration_by_phone() throws Exception {
        long startTime = System.currentTimeMillis();
        String startedAt = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss").format(new Date(startTime));

        System.out.println("=== СТАРТ ТЕСТА v2_phone_registration ===");
        tg.sendMessage(
                "🕒 *Тест v2_phone_registration* стартовал\n" +
                        "• Время старта: " + startedAt + "\n" +
                        "• Сценарий: регистрация по номеру телефона"
        );

        String sentLogin = null;
        String sentPassword = null;

        try {
            // --- ОТКРЫВАЕМ САЙТ ---
            System.out.println("Открываем сайт: https://1xbet.kz/?platform_type=desktop");
            page.navigate("https://1xbet.kz/?platform_type=desktop");
            pauseMedium();

            // --- НАЖИМАЕМ 'РЕГИСТРАЦИЯ' ---
            System.out.println("Нажимаем кнопку 'Регистрация' на главной странице...");
            waitAndClick(page, "button#registration-form-call", 15_000);

            // --- ЖДЁМ МОДАЛКУ РЕГИСТРАЦИИ ---
            waitForRegistrationModal(page);

            System.out.println("Остаёмся на форме регистрации по номеру телефона (без клика 'В 1 клик').");

            // --- ПРОМОКОД ---
            String promo = randomPromo(8);
            System.out.println("Генерируем промокод: " + promo);
            Locator promoInput = page.locator("input#popup_registration_ref_code");
            if (promoInput.count() > 0 && promoInput.first().isVisible()) {
                System.out.println("Вводим промокод в поле...");
                promoInput.first().fill(promo);
            } else {
                System.out.println("Поле промокода не найдено/не видно, пропускаем ввод.");
            }

            // --- БОНУСЫ ---
            System.out.println("Пробуем закрыть вариант 'Без бонуса' (если есть)...");
            clickIfVisible(page, "div.c-registration-bonus__item.c-registration-bonus__item--close");

            System.out.println("Пробуем выбрать бонус 'Принять' (если есть)...");
            clickIfVisible(page, "div.c-registration-bonus__item:has(.c-registration-bonus__title:has-text('Принять'))");

            // --- ВВОД НОМЕРА ТЕЛЕФОНА ---
            System.out.println("Готовим ввод номера телефона из конфига...");
            String phone = ConfigHelper.get("phone");
            System.out.println("Вводим номер телефона: " + phone);
            Locator phoneInput = page.locator("input[id^='auth_phone_number_'], input.phone-input__field[type='tel']");
            if (phoneInput.count() > 0 && phoneInput.first().isVisible()) {
                phoneInput.first().fill(phone);
            } else {
                throw new RuntimeException("Поле 'Номер телефона' не найдено на форме регистрации по телефону.");
            }

            // --- ОТПРАВИТЬ SMS ---
            System.out.println("Жмём 'Отправить sms'...");
            Locator sendSmsBtn = page.locator("button#button_send_sms:has-text('Отправить sms')");
            if (sendSmsBtn.count() == 0 || !sendSmsBtn.first().isVisible()) {
                throw new RuntimeException("Кнопка 'Отправить sms' не найдена.");
            }
            sendSmsBtn.first().click();

            page.waitForTimeout(60000);

// --- ЖДЁМ КАПЧУ, ЕСЛИ ОНА ВООБЩЕ ПОЯВИТСЯ ---
            waitUserSolvesCaptchaIfAppears(page);

// --- ПОПАП 'ОК' ПОСЛЕ ОТПРАВКИ SMS (если есть) ---
            System.out.println("Пробуем нажать 'ОК' в попапе после отправки SMS (если он появился)...");
            clickIfVisible(page, "button.swal2-confirm.swal2-styled:has-text('ОК')");
            clickIfVisible(page, "button.swal2-confirm.swal2-styled:has-text('OK')");

// --- ЖДЁМ ПОЛЕ ДЛЯ КОДА ---
            System.out.println("Жду появления поля 'Код подтверждения' (до 10 минут)...");
            page.waitForSelector("input[placeholder='Код подтверждения']",
                    new Page.WaitForSelectorOptions()
                            .setTimeout(600_000)
                            .setState(WaitForSelectorState.VISIBLE)
            );
            System.out.println("Поле 'Код подтверждения' появилось ✅");

// --- ДАЁМ 5 СЕКУНД НА ПРИХОД НОВОГО SMS ---
            page.waitForTimeout(5000);

// --- ТЕПЕРЬ БЕРЁМ КОД ИЗ GOOGLE MESSAGES ---
            String smsCode = fetchSmsCodeFromGoogleMessages();
            System.out.println("Код подтверждения из SMS: " + smsCode);

// --- ВВОДИМ КОД ПОДТВЕРЖДЕНИЯ ---
            System.out.println("Вводим код подтверждения в поле 'Код подтверждения'");
            Locator codeInput = page.locator("input#popup_registration_phone_confirmation");
            codeInput.fill(smsCode);

// --- ЖМЁМ 'ПОДТВЕРДИТЬ' ---
            System.out.println("Жмём 'Подтвердить'");
            Locator confirmBtn = page.locator("button.confirm_sms.reg_button_sms.c-registration__button--inside:has-text('Подтвердить')");
            if (confirmBtn.count() == 0 || !confirmBtn.first().isVisible()) {
                throw new RuntimeException("Кнопка 'Подтвердить' не найдена.");
            }
            confirmBtn.first().click();

            // --- ГАЛОЧКА СОГЛАСИЯ ---
            System.out.println("Ставим галочку согласия с правилами (agree-policy)...");
            Locator agreeCheckbox = page.locator("label.c-registration-check__text[for^='agree-policy']");
            if (agreeCheckbox.count() > 0 && agreeCheckbox.first().isVisible()) {
                agreeCheckbox.first().click();
            } else {
                System.out.println("Галочка agree-policy не найдена или не видна, возможно уже отмечена.");
            }

            // --- ЖДЁМ АКТИВАЦИЮ КНОПКИ 'ЗАРЕГИСТРИРОВАТЬСЯ' ---
            System.out.println("Ждём, пока кнопка 'Зарегистрироваться' станет активной...");
            page.waitForFunction(
                    "document.querySelector('div.c-registration__button.submit_registration') && " +
                            "!document.querySelector('div.c-registration__button.submit_registration').classList.contains('disabled')"
            );
            System.out.println("Кнопка 'Зарегистрироваться' активна ✅");

            // --- НАЖИМАЕМ 'ЗАРЕГИСТРИРОВАТЬСЯ' ---
            System.out.println("Жмём 'Зарегистрироваться' (через JS)...");

            Locator regBtn = page.locator("div.c-registration__button.submit_registration:has-text('Зарегистрироваться')");
            if (regBtn.count() > 0 && regBtn.first().isVisible()) {
                page.evaluate("el => el.click()", regBtn.first().elementHandle());
                System.out.println("JS-клик по 'Зарегистрироваться' выполнен.");
            } else {
                throw new RuntimeException("Кнопка 'Зарегистрироваться' не найдена или не видна.");
            }

            // --- ЖДЁМ ОКНО С ЛОГИНОМ/ПАРОЛЕМ ---
            System.out.println("Ждём появление окна 'Благодарим за регистрацию' / блок с логином и паролем...");
            page.waitForSelector("p#account-info-id",
                    new Page.WaitForSelectorOptions().setTimeout(120_000).setState(WaitForSelectorState.VISIBLE));
            page.waitForSelector("p#account-info-password",
                    new Page.WaitForSelectorOptions().setTimeout(120_000).setState(WaitForSelectorState.VISIBLE));
            System.out.println("Окно с логином и паролем появилось ✅");

            // --- ИЗВЛЕКАЕМ КРЕДЫ ---
            System.out.println("Читаем логин и пароль...");
            String login = page.locator("p#account-info-id").innerText().trim();
            String password = page.locator("p#account-info-password").innerText().trim();
            sentLogin = login;
            sentPassword = password;
            System.out.println("Логин: " + login + ", Пароль: " + password);

            tg.sendMessage(
                    "🔑 Регистрация по телефону завершена\n" +
                            "• Логин: `" + login + "`\n" +
                            "• Пароль: `" + password + "`"
            );

            // --- КОПИРУЕМ КРЕДЫ ---
            System.out.println("Жмём 'Копировать логин и пароль'...");
            clickIfVisible(page, "div#js-post-reg-copy-login-password");
            System.out.println("Подтверждаем всплывающее сообщение 'ОК' после копирования...");
            clickIfVisible(page, "button.swal2-confirm.swal2-styled:has-text('ОК')");
            pauseMedium();

            // --- ПОЛУЧИТЬ ПО SMS ---
            System.out.println("Жмём 'Получить по SMS'...");
            clickIfVisible(page, "button#account-info-button-sms");
            pauseMedium();
            closeAllKnownPopups(page, "После 'Получить по SMS' (без привязки номера)");

            // --- СОХРАНИТЬ В ФАЙЛ ---
            System.out.println("Жмём 'Сохранить в файл'...");
            clickIfVisible(page, "a#account-info-button-file");
            pauseMedium();
            closeAllKnownPopups(page, "После 'Сохранить в файл'");

            // --- СОХРАНИТЬ КАРТИНКОЙ ---
            System.out.println("Жмём 'Сохранить картинкой'...");
            clickIfVisible(page, "a#account-info-button-image");
            pauseMedium();
            closeAllKnownPopups(page, "После 'Сохранить картинкой'");

            // --- ВЫСЛАТЬ НА EMAIL ---
            System.out.println("Жмём 'Выслать на e-mail'...");
            clickIfVisible(page, "a#form_mail_after_submit");

            System.out.println("Пробуем ввести email и отправить...");
            Locator emailInput = page.locator("input.js-post-email-content-form__input[type='email']");
            if (emailInput.count() > 0 && emailInput.first().isVisible()) {
                String email = ConfigHelper.get("email");
                System.out.println("Вводим email: " + email);
                emailInput.first().fill(email);
                pauseShort();
                Locator sendBtn = page.locator("button.js-post-email-content-form__btn:not([disabled])");
                if (sendBtn.count() > 0) {
                    System.out.println("Жмём кнопку отправки email...");
                    sendBtn.first().click();
                    System.out.println("Email отправлен ✅");
                } else {
                    System.out.println("Кнопка отправки email не найдена/заблокирована.");
                }
            } else {
                System.out.println("Поле email не найдено/не видно, пропускаем отправку.");
            }
            pauseMedium();
            closeAllKnownPopups(page, "После 'Выслать на e-mail'");

            // --- КЛИКАЕМ НА БАННЕР 'ПОЛУЧИТЬ БОНУС' ---
            System.out.println("Кликаем по баннеру 'Получить бонус' (если есть)...");
            clickIfVisible(page, "span#form_get_bonus_after_submit");
            pauseMedium();
            closeAllKnownPopups(page, "После 'Получить бонус'");

            // --- ПЕРЕХОД В ЛИЧНЫЙ КАБИНЕТ ЧЕРЕЗ КНОПКУ В ШАПКЕ ---
            System.out.println("Переходим в Личный кабинет через кнопку в шапке...");
            clickIfVisible(page, "a.header-lk-box-link[title='Личный кабинет']");

            // ждём загрузку ЛК
            page.waitForLoadState();
            System.out.println("Страница Личного кабинета загружена.");
            closeAllKnownPopups(page, "Личный кабинет после перехода");

            // --- ВЫХОД ---
            System.out.println("Ищем кнопку 'Выход' в боковом меню...");
            Locator logoutBtn = page.locator("a.ap-left-nav__item.ap-left-nav__item_exit:has-text('Выход')");
            if (logoutBtn.count() > 0 && logoutBtn.first().isVisible()) {
                System.out.println("Кнопка 'Выход' найдена, кликаем...");
                logoutBtn.first().click();
                System.out.println("Подтверждаем выход в модальном окне 'ОК'...");
                clickIfVisible(page, "button.swal2-confirm.swal2-styled:has-text('ОК')");
            } else {
                System.out.println("Кнопка 'Выход' не найдена, возможно уже не авторизованы.");
            }

            System.out.println("Выход из аккаунта завершён (по шагам) ✅");

            // --- ФИНАЛЬНОЕ РЕЗЮМЕ ---
            long duration = (System.currentTimeMillis() - startTime) / 1000;
            String summary =
                    "✅ *Тест завершён успешно:* v2_phone_registration\n" +
                            "• Старт: " + startedAt + "\n" +
                            "• Сценарий: регистрация по номеру телефона\n" +
                            "• Основные шаги:\n" +
                            "  1) Открытие сайта и формы регистрации\n" +
                            "  2) Ввод номера и подтверждение по SMS\n" +
                            "  3) Получение логина/пароля и доп. действия (SMS, файл, картинка, e-mail)\n" +
                            "  4) Переход в ЛК и выход из аккаунта\n" +
                            "• Логин: `" + sentLogin + "`\n" +
                            "• Пароль: `" + sentPassword + "`\n" +
                            "🕒 Длительность: " + duration + " сек.\n" +
                            "🌐 [1xbet.kz](https://1xbet.kz)";
            tg.sendMessage(summary);
            System.out.println("=== ТЕСТ УСПЕШНО ЗАВЕРШЁН за " + duration + " сек. ===");

        } catch (Exception e) {
            System.out.println("❌ Ошибка во время выполнения теста: " + e);
            String screenshotPath = ScreenshotHelper.takeScreenshot(page, "v2_phone_registration");
            System.out.println("Скриншот сохранён по пути: " + screenshotPath);
            tg.sendMessage("🚨 Ошибка в v2_phone_registration: " + e.getMessage());
            if (screenshotPath != null) tg.sendPhoto(screenshotPath, "Скриншот ошибки");
            throw e;
        }
    }
}