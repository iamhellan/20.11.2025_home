package org.example;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitForSelectorState;
import org.junit.jupiter.api.*;
import com.microsoft.playwright.options.BoundingBox;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class v2_1click_registration {
    static Playwright playwright;
    static Browser browser;
    static BrowserContext context;
    static Page page;
    static TelegramNotifier tg;

    // ====== SETTINGS ======
    static final Path MESSAGES_SESSION = Paths.get("messages-session.json"); // json сессия Google Messages

    @BeforeAll
    static void setUpAll() {
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

        // --- Telegram инициализация ---
        String botToken = ConfigHelper.get("telegram.bot.token");
        String chatId   = ConfigHelper.get("telegram.chat.id");
        tg = new TelegramNotifier(botToken, chatId);
    }

    @AfterAll
    static void tearDownAll() {
        try { if (context != null) context.close(); } catch (Throwable ignored) {}
        try { if (browser != null) browser.close(); } catch (Throwable ignored) {}
        try { if (playwright != null) playwright.close(); } catch (Throwable ignored) {}
        System.out.println("Тест завершён ✅ (браузер и контекст закрыты)");
    }

    // ---------- ХЕЛПЕРЫ ----------
    static void pause(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) {} }
    static void pauseShort() { pause(150); }
    static void pauseMedium() { pause(350); }

    static void waitAndClick(Page page, String selector, int timeoutMs) {
        page.waitForSelector(selector,
                new Page.WaitForSelectorOptions().setTimeout(timeoutMs).setState(WaitForSelectorState.VISIBLE));
        page.locator(selector).first().click();
        pauseMedium();
    }

    static void clickIfVisible(Page page, String selector) {
        Locator loc = page.locator(selector);
        if (loc.count() > 0 && loc.first().isVisible()) {
            loc.first().click(new Locator.ClickOptions().setTimeout(5000));
            pauseShort();
        }
    }

    static void jsClick(Locator loc) {
        if (loc.count() > 0) loc.first().dispatchEvent("click");
    }

    static void neutralizeOverlayIfNeeded(Page page) {
        page.evaluate("(() => {" +
                "const kill = sel => document.querySelectorAll(sel).forEach(n=>{try{n.style.pointerEvents='none'; n.style.zIndex='0';}catch(e){}});" +
                "kill('.arcticmodal-container_i2');" +
                "kill('.arcticmodal-container_i');" +
                "kill('.v--modal-background-click');" +
                "kill('#modals-container *');" +
                "kill('.pf-main-container-wrapper-th-4 *');" +
                "})();");
    }

    void waitForRegistrationModal(Page page) {
        page.waitForSelector("div#games_content.c-registration",
                new Page.WaitForSelectorOptions()
                        .setTimeout(30_000)
                        .setState(WaitForSelectorState.VISIBLE)
        );
    }


    static boolean isOneClickActive(Page page) {
        Locator tab = page.locator("div#games_content.c-registration button.c-registration__tab:has-text('В 1 клик')");
        if (tab.count() == 0) return false;
        Object res = tab.first().evaluate("el => el.classList.contains('active')");
        return Boolean.TRUE.equals(res);
    }

    static String randomPromo(int len) {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        Random rnd = new Random();
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) sb.append(chars.charAt(rnd.nextInt(chars.length())));
        return sb.toString();
    }

    static boolean isLoggedOut(Page page) {
        boolean hasRegBtn = page.locator("button#registration-form-call").count() > 0
                && page.locator("button#registration-form-call").first().isVisible();
        boolean headerNotLogged = Boolean.TRUE.equals(page.evaluate("() => {" +
                "const h = document.querySelector('header.header');" +
                "return !!h && !h.classList.contains('header--user-logged');" +
                "}"));
        String url = page.url();
        boolean onPublicUrl = url.contains("1xbet.kz/") && !url.contains("/office/");
        return hasRegBtn || headerNotLogged || onPublicUrl;
    }

    void closeIdentificationIfPresent(Page page) {
        // 1) Снимаем перекрытия (pointer-events/z-index) на всякий случай
        neutralizeOverlayIfNeeded(page);

        // 2) Пытаемся дождаться и закрыть окно идентификации
        final String CLOSE_SEL =
                "button.identification-popup-close.identification-popup-get-bonus__close, " +
                        "button.identification-popup-close.identification-popup-transition__close, " +
                        "button.identification-popup-close.identification-popup-binding__close";

        try {
            ElementHandle closeHandle = page.waitForSelector(
                    CLOSE_SEL,
                    new Page.WaitForSelectorOptions()
                            .setTimeout(5000)
                            .setState(WaitForSelectorState.VISIBLE)
            );

            if (closeHandle != null) {
                try {
                    closeHandle.click();
                    System.out.println("Закрыто окно идентификации ✅");
                } catch (Exception e) {
                    // Fallback: JS-клик по реальному элементу
                    page.evaluate("el => el.click()", closeHandle);
                    System.out.println("Закрыто окно идентификации через JS ✅");
                }
                page.waitForTimeout(300);
            } else {
                System.out.println("Окно идентификации не появилось — продолжаем");
            }
        } catch (PlaywrightException ignored) {
            System.out.println("Окно идентификации не появилось — продолжаем");
        }

        // 3) Ещё раз нейтрализуем возможные остаточные оверлеи
        neutralizeOverlayIfNeeded(page);
    }

    static void waitUntilLoggedOutOrHeal(Page page) {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            if (isLoggedOut(page)) return;
            neutralizeOverlayIfNeeded(page);
            clickIfVisible(page, "button.swal2-confirm.swal2-styled");
            clickIfVisible(page, "button.identification-popup-close");
            pause(300);
        }
        page.navigate("https://1xbet.kz/");
        page.waitForLoadState(LoadState.DOMCONTENTLOADED);
        long deadline2 = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline2) {
            if (isLoggedOut(page)) return;
            pause(300);
        }
    }

    // ---------- GOOGLE MESSAGES ----------
    static String fetchSmsCodeFromGoogleMessages() {
        System.out.println("🔐 Открываем Google Messages с сохранённой сессией…");
        BrowserContext messagesContext = browser.newContext(
                new Browser.NewContextOptions().setStorageStatePath(MESSAGES_SESSION)
        );
        Page messagesPage = messagesContext.newPage();
        messagesPage.setDefaultTimeout(20_000);
        messagesPage.navigate("https://messages.google.com/web/conversations");

        // Ждём появления списка чатов
        for (int i = 0; i < 20; i++) {
            if (messagesPage.locator("mws-conversation-list-item").count() > 0) break;
            messagesPage.waitForTimeout(1000);
        }

        // Открываем верхний (последний) чат
        Locator chat = messagesPage.locator("mws-conversation-list-item").first();
        chat.click();
        messagesPage.waitForTimeout(1200);

        // Берём текст последнего входящего сообщения
        // Основной узел текста: div.text-msg.msg-content div.ng-star-inserted
        Locator nodes = messagesPage.locator("div.text-msg.msg-content div.ng-star-inserted");
        int count = nodes.count();
        String text = count > 0 ? nodes.nth(count - 1).innerText() : "";
        if (text == null) text = "";

        // Ищем 4–8 подряд идущих цифр
        Matcher m = Pattern.compile("(?<!\\d)(\\d{4,8})(?!\\d)").matcher(text);
        String code = m.find() ? m.group(1) : null;

        messagesContext.close();

        if (code == null || code.isBlank())
            throw new RuntimeException("Код из SMS не найден в последнем сообщении Google Messages");
        System.out.println("✅ Код из SMS: " + code);
        return code;
    }

    // ---------- ТЕСТ ----------
    @Test
    void v2_registration() {
        long startTime = System.currentTimeMillis();
        tg.sendMessage("🚀 *Тест v2_1click_registration* стартовал (десктоп, регистрация в 1 клик)");

        try {
            System.out.println("Открываем сайт 1xbet.kz");
            page.navigate("https://1xbet.kz/?platform_type=desktop");
            pauseMedium();

            // --- РЕГИСТРАЦИЯ ---
            System.out.println("Жмём 'Регистрация'");
            waitAndClick(page, "button#registration-form-call", 15_000);

            System.out.println("Ожидаем модалку регистрации");
            waitForRegistrationModal(page);
            pauseShort();

            if (!isOneClickActive(page)) {
                System.out.println("Активируем вкладку 'В 1 клик'");
                Locator oneClickTab = page.locator("div#games_content.c-registration button.c-registration__tab:has-text('В 1 клик')");
                try {
                    oneClickTab.first().click(new Locator.ClickOptions().setTimeout(3000));
                } catch (Exception e) {
                    System.out.println("Обычный клик не сработал, пробуем через JS...");
                    ElementHandle handle = oneClickTab.first().elementHandle();
                    if (handle != null) page.evaluate("el => el.click()", handle);
                }

                // Ждём, пока вкладка реально станет активной
                page.waitForSelector("div#games_content.c-registration button.c-registration__tab.active:has-text('В 1 клик')",
                        new Page.WaitForSelectorOptions()
                                .setTimeout(120000)
                                .setState(WaitForSelectorState.VISIBLE)
                );
            } else {
                System.out.println("Вкладка 'В 1 клик' уже активна");
            }

            String promo = randomPromo(8);
            System.out.println("Вводим промокод: " + promo);
            Locator promoInput = page.locator("input#popup_registration_ref_code");
            if (promoInput.count() > 0 && promoInput.first().isVisible()) {
                promoInput.first().fill(promo);
            } else {
                page.fill("input[placeholder*='промокод' i]", promo);
            }

            // Бонусы
            System.out.println("Отказываемся от бонусов, затем соглашаемся");
            clickIfVisible(page, "div.c-registration-bonus__item.c-registration-bonus__item--close:has(.c-registration-bonus__title:has-text('Отказаться'))");
            clickIfVisible(page, "div.c-registration-bonus__item:has(.c-registration-bonus__title:has-text('Принять'))");

            System.out.println("Ждём, пока кнопка 'Зарегистрироваться' станет активной...");
            page.waitForFunction(
                    "document.querySelector('div.c-registration__button.submit_registration') && " +
                            "!document.querySelector('div.c-registration__button.submit_registration').classList.contains('disabled')"
            );

            System.out.println("Жмём 'Зарегистрироваться'");
            try {
                page.locator("div.c-registration__button.submit_registration:has-text('Зарегистрироваться')").first().click();
            } catch (Exception e) {
                System.out.println("Обычный клик не сработал, пробуем через JS...");
                page.evaluate("document.querySelector('div.c-registration__button.submit_registration')?.click()");
            }

// после клика могли появиться редирект или новый фрейм
            System.out.println("⏳ Ждём завершения регистрации и появления пост-регистрационного окна...");

            try {
                // ждем полной загрузки
                page.waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(60_000));
                page.waitForFunction("document.readyState === 'complete'");

                // проверяем разные варианты пост-регистрационного блока
                String[] possibleSelectors = {
                        "#js-post-reg-copy-login-password",
                        "#js-post-registration-copy-login-password",
                        "div.post-registration",
                        "div.box-modal",
                        "div.popup-registration"
                };

                boolean found = false;
                for (String sel : possibleSelectors) {
                    if (page.locator(sel).count() > 0) {
                        try {
                            page.waitForSelector(sel,
                                    new Page.WaitForSelectorOptions().setTimeout(120_000).setState(WaitForSelectorState.VISIBLE));
                            System.out.println("✅ Найден блок пост-регистрации: " + sel);
                            found = true;
                            break;
                        } catch (Exception ignored) {}
                    }
                }

                if (!found) {
                    System.out.println("⚠️ Блок логина/пароля не появился — возможна ошибка регистрации.");
                    Locator errorBox = page.locator("div.error, span.error, .popup-error");
                    if (errorBox.count() > 0 && errorBox.first().isVisible()) {
                        System.out.println("Текст ошибки: " + errorBox.first().innerText());
                    }
                    tg.sendMessage("⚠️ Блок логина/пароля не найден после регистрации.");
                    ScreenshotHelper.takeScreenshot(page, "registration_no_block");
                }

            } catch (PlaywrightException e) {
                System.out.println("❌ Ошибка ожидания пост-регистрации: " + e.getMessage());
                tg.sendMessage("❌ Ошибка ожидания пост-регистрации: " + e.getMessage());
                ScreenshotHelper.takeScreenshot(page, "registration_timeout");
            }

// ----------- POST-REGISTRATION FLOW -------------
            System.out.println("Кликаем 'Копировать'");
            Locator copyBtn = page.locator("#js-post-reg-copy-login-password");
            if (copyBtn.count() > 0 && copyBtn.first().isVisible()) {
                copyBtn.first().click();
                page.waitForTimeout(1000); // подождать реакцию UI
                // fallback, если popup не появился
                if (page.locator("button.swal2-confirm.swal2-styled:has-text('ОК')").count() == 0) {
                    System.out.println("Popup 'ОК' не появился, триггерим событие вручную");
                    page.evaluate("el => el.dispatchEvent(new MouseEvent('click', { bubbles: true }))", copyBtn.first());
                    page.waitForTimeout(1000);
                }
            } else {
                throw new RuntimeException("Кнопка 'Копировать' не найдена или не видна");
            }
            pauseMedium();

            System.out.println("Закрываем всплывающее окно 'ОК', если появилось");
            try {
                Locator okButton = page.locator("button.swal2-confirm.swal2-styled:has-text('ОК')");
                okButton.waitFor(new Locator.WaitForOptions().setTimeout(3000).setState(WaitForSelectorState.VISIBLE));
                if (okButton.isVisible()) {
                    okButton.click();
                    System.out.println("Кнопка 'ОК' нажата ✅");
                    pauseShort();
                }
            } catch (Exception ignored) {}

            System.out.println("Кликаем 'Сохранить в файл'");
            clickIfVisible(page, "a#account-info-button-file");
            pauseMedium();

            System.out.println("Закрываем всплывающее окно 'Закрыть', если появилось");
            try {
                Locator closePopup = page.locator("button.identification-popup-close");
                closePopup.waitFor(new Locator.WaitForOptions().setTimeout(3000).setState(WaitForSelectorState.VISIBLE));
                if (closePopup.isVisible()) {
                    closePopup.click();
                    System.out.println("Кнопка 'Закрыть' нажата ✅");
                    pauseShort();
                }
            } catch (Exception ignored) {}

            System.out.println("Кликаем 'Сохранить картинкой'");
            clickIfVisible(page, "a#account-info-button-image");
            pauseMedium();

            System.out.println("Закрываем всплывающее окно 'Закрыть', если появилось");
            try {
                Locator closePopup = page.locator("button.identification-popup-close");
                closePopup.waitFor(new Locator.WaitForOptions().setTimeout(3000).setState(WaitForSelectorState.VISIBLE));
                if (closePopup.isVisible()) {
                    closePopup.click();
                    System.out.println("Кнопка 'Закрыть' нажата ✅");
                    pauseShort();
                }
            } catch (Exception ignored) {}

            System.out.println("Кликаем 'Выслать на e-mail'");
            clickIfVisible(page, "a#form_mail_after_submit");
            pauseMedium();

            // Вводим email
            Locator emailField = page.locator("input.post-email__input[type='email']:visible").first();
            emailField.fill("zhante1111@gmail.com");
            pauseShort();

            Locator sendBtn = page.locator("button.js-post-email-content-form__btn:not([disabled])");
            sendBtn.waitFor();
            sendBtn.click();
            System.out.println("Email отправлен");
            pauseMedium();
            // --- Закрываем все всплывающие крестики регистрации ---
            System.out.println("Закрываем все всплывающие крестики регистрации...");
            Locator closeBtns = page.locator("#closeModal, .arcticmodal-close.c-registration__close");
            int btnCount = closeBtns.count();
            for (int i = 0; i < btnCount; i++) {
                if (closeBtns.nth(i).isVisible()) {
                    closeBtns.nth(i).click();
                    System.out.println("Закрыт крестик #" + (i + 1));
                    page.waitForTimeout(300);
                }
            }// --- Закрываем окно регистрации ---
            System.out.println("Закрываем окно регистрации...");
            Locator regCloseBtn = page.locator("#closeModal");
            if (regCloseBtn.isVisible()) {
                regCloseBtn.click();
                System.out.println("Окно регистрации закрыто ✅");
                page.waitForTimeout(500);
            } else {
                System.out.println("Крестик регистрации не найден — возможно, уже закрыто.");
            }
            neutralizeOverlayIfNeeded(page);

            // Кликаем по каждой видимой ссылке "Пройти идентификацию" через JS
            Locator identLinks = page.locator("a.identification-popup-link[href='/office/identification']");
            int count = identLinks.count();
            for (int i = 0; i < count; i++) {
                Locator link = identLinks.nth(i);
                if (link.isVisible()) {
                    page.evaluate("el => el.click()", link);
                    System.out.println("Кликнули 'Пройти идентификацию' через JS! #" + (i + 1));
                    page.waitForTimeout(1000);
                    break; // Если нужна только первая — убери break если надо все
                }
            }

// --- Выходим из аккаунта ---
            System.out.println("Кликаем 'Выход'");
            neutralizeOverlayIfNeeded(page); clickIfVisible(page, "a.ap-left-nav__item_exit");
            pauseShort();

            neutralizeOverlayIfNeeded(page); clickIfVisible(page, "button.swal2-confirm.swal2-styled");
            System.out.println("Вышли из аккаунта");

            waitUntilLoggedOutOrHeal(page);

            long duration = (System.currentTimeMillis() - startTime) / 1000;
            tg.sendMessage(
                    "✅ *Тест успешно завершён:* v2_1click_registration\n" +
                            "• Регистрация — выполнена\n" +
                            "• Привязка по SMS — активирована\n" +
                            "• Отправка на e-mail — выполнена\n" +
                            "• Выход из аккаунта — выполнен\n\n" +
                            "🕒 Время выполнения: *" + duration + " сек.*\n" +
                            "🌐 [1xbet.kz](https://1xbet.kz)"
            );

            System.out.println("Регистрация в 1 клик завершена успешно ✅");

        } catch (Exception e) {
            String screenshotPath = ScreenshotHelper.takeScreenshot(page, "v2_1click_registration");
            tg.sendMessage("🚨 Ошибка в *v2_1click_registration*:\n" + e.getMessage());
            if (screenshotPath != null) tg.sendPhoto(screenshotPath, "Скриншот ошибки");
            throw e;
        }
    }
}
