package org.example;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitForSelectorState;
import org.junit.jupiter.api.*;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class v2_social_registration {
    static Playwright playwright;
    static Browser browser;
    static BrowserContext context;
    static Page page;
    static TelegramNotifier tg;

    static final SimpleDateFormat DATE_TIME_FORMAT = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss");
    static final long GOOGLE_FLOW_MAX_WAIT_MS = 300_000L; // 5 минут

    // --- СЕЛЕКТОРЫ КРЕСТИКОВ / КНОПОК ЗАКРЫТИЯ ПОПАПОВ ---
    static final String[] POPUP_CLOSE_SELECTORS = new String[]{
            // --- арктик-модалки ---
            "div.box-modal_close.arcticmodal-close",
            ".arcticmodal-close",
            "div.box-modal_close",

            // --- overlay, который блокирует клики ---
            "div.v--modal-background-click",
            ".v--modal-overlay",

            // --- контейнеры модалок Vue / 1xBet ---
            "div.v--modal-box",
            "div.v--modal",

            // --- общий крестик ---
            "button[title='Закрыть']",

            // --- окна регистрации / пост-регистрации ---
            "button.popup-registration__close",

            // --- идентификация / привязка / бонусы / переходы ---
            "button.identification-popup-close.identification-popup-binding__close",
            "button.identification-popup-close.identification-popup-get-bonus__close",
            "button.identification-popup-close.identification-popup-transition__close",

            // --- восстановление пароля ---
            "button.reset-password__close",

            // --- Vue UI (иногда появляется) ---
            "button.v--modal-close-btn",

            // --- общий случай ---
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

    static boolean isVisible(Page page, String selector) {
        Locator loc = page.locator(selector);
        return loc.count() > 0 && loc.first().isVisible();
    }

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

    // пока не используется в основном флоу, но оставляем как задел
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
                        } catch (Exception ignored) {}
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

    // --- ПАРСИНГ ID (если удастся найти где-нибудь на странице) ---
    static String tryExtractAccountId(Page page) {
        try {
            String body = page.innerText("body");
            Matcher m = Pattern.compile("(ID|Id|id)\\s*[:\\-]?\\s*(\\d{5,})").matcher(body);
            if (m.find()) {
                return m.group(2);
            }
        } catch (Exception ignored) {}
        return null;
    }

    // --- РЕЗЕРВНЫЙ ПАРСИНГ ЛОГИН/ПАРОЛЬ ИЗ ТЕКСТА СТРАНИЦЫ ---
    static Map<String, String> extractCredentials(Page page) {
        System.out.println("Пробуем извлечь логин/пароль из текста страницы (резервный метод)...");
        String login = null, password = null;
        try {
            String txt = page.innerText("body");
            Matcher ml = Pattern.compile("Логин\\s*[:\\-]?\\s*(\\S+)", Pattern.CASE_INSENSITIVE).matcher(txt);
            if (ml.find()) login = ml.group(1);
            Matcher mp = Pattern.compile("Пароль\\s*[:\\-]?\\s*(\\S+)", Pattern.CASE_INSENSITIVE).matcher(txt);
            if (mp.find()) password = mp.group(1);
        } catch (Exception e) {
            System.out.println("Ошибка при парсинге кредов: " + e.getMessage());
        }
        Map<String, String> out = new HashMap<>();
        out.put("login", login);
        out.put("password", password);
        System.out.println("Извлечение завершено. Логин=" + login + ", Пароль=" + password);
        return out;
    }

    // --- ЛОГИН В GOOGLE-ОКНЕ / ТЕКУЩЕЙ ВКЛАДКЕ ---
    static void performGoogleLogin(Page googlePage, String googleEmail, String googlePassword) {
        System.out.println("Окно/вкладка Google открыта, начинаем авторизацию...");

        // Иногда уже показывается выбор аккаунта — пробуем сразу кликнуть по нашему email
        try {
            Locator accountTile = googlePage.locator("div[role='button']:has-text('" + googleEmail + "')");
            if (accountTile.count() > 0 && accountTile.first().isVisible()) {
                System.out.println("Нашли плитку с email " + googleEmail + ", кликаем...");
                accountTile.first().click();
            }
        } catch (Exception ignored) {}

        // Шаг 1: ввод email (если поле есть)
        Locator emailInput = googlePage.locator("input[type='email']");
        if (emailInput.count() > 0 && emailInput.first().isVisible()) {
            System.out.println("Вводим email в форму Google");
            emailInput.first().fill(googleEmail);
            googlePage.locator("button:has-text('Далее'), div:has-text('Далее')").first().click();
        } else {
            System.out.println("Поле email не найдено/не видно — возможно, уже выбрали аккаунт.");
        }

        // Ждём появление поля пароля
        try {
            googlePage.waitForSelector("input[type='password']",
                    new Page.WaitForSelectorOptions()
                            .setTimeout(60_000)
                            .setState(WaitForSelectorState.VISIBLE)
            );
            System.out.println("Поле пароля появилось, вводим пароль...");
            googlePage.locator("input[type='password']").first().fill(googlePassword);
            googlePage.locator("button:has-text('Далее'), div:has-text('Далее')").first().click();
        } catch (PlaywrightException e) {
            System.out.println("Поле пароля так и не появилось — возможно, сработал выбор аккаунта без пароля.");
        }

        // Иногда спрашивают «Оставаться в системе?» и т.п.
        try {
            Locator denyBtn = googlePage.locator("button:has-text('Нет'), button:has-text('Нет, спасибо'), button:has-text('Не сейчас')");
            if (denyBtn.count() > 0 && denyBtn.first().isVisible()) {
                System.out.println("Закрываем доп. диалог Google ('Не сейчас' и т.п.)");
                denyBtn.first().click();
            }
        } catch (Exception ignored) {}

        // Ждём, пока Google закончит редирект (URL перестанет быть google.*)
        long start = System.currentTimeMillis();
        long maxWait = 120_000L;
        while (System.currentTimeMillis() - start < maxWait) {
            String url = "";
            try {
                url = googlePage.url();
            } catch (Exception ignored) {}
            if (!url.contains("accounts.google.") && !url.contains("consent.google.") && !url.contains("myaccount.google.")) {
                System.out.println("URL больше не Google: " + url);
                break;
            }
            googlePage.waitForTimeout(1000);
        }
        System.out.println("Google-авторизация завершена (по данным URL/таймауту).");
    }

    @Test
    void v2_social_registration_google() {
        long startMs = System.currentTimeMillis();
        String startedAt = DATE_TIME_FORMAT.format(new Date());
        String testName = "v2_social_registration_google";

        String googleEmail = ConfigHelper.get("google.email");
        String googlePassword = ConfigHelper.get("google.password");

        // ранняя проверка конфигурации
        if (googleEmail == null || googleEmail.isBlank() ||
                googlePassword == null || googlePassword.isBlank()) {
            throw new IllegalStateException(
                    "google.email / google.password не заданы в config.properties — " +
                            "некорректно запускать тест соцрегистрации через Google.");
        }

        System.out.println("=== СТАРТ ТЕСТА " + testName + " ===");
        tg.sendMessage(
                "🕒 " + startedAt + "\n" +
                        "🚀 *Тест " + testName + "* стартовал\n" +
                        "• Тип: регистрация через Google (соцсети)"
        );

        String accountId = null;
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

            // --- ПЕРЕКЛЮЧАЕМСЯ НА ВКЛАДКУ 'СОЦСЕТИ И МЕССЕНДЖЕРЫ' ---
            System.out.println("Переходим на вкладку 'Соцсети и мессенджеры'...");
            Locator socialTab = page.locator(
                    "button.c-registration__tab.soc_reg, " +
                            "button.c-registration__tab:has-text('Соцсети и мессенджеры')"
            );
            if (socialTab.count() == 0 || !socialTab.first().isVisible()) {
                throw new RuntimeException("Таб 'Соцсети и мессенджеры' не найден.");
            }
            socialTab.first().click();
            pauseShort();

            // --- ЖДЁМ ОТОБРАЖЕНИЯ КНОПКИ GOOGLE ---
            System.out.println("Ждём, пока появится вариант 'Google'...");
            page.waitForSelector(
                    "div.c-registration__social-inner[name='google']",
                    new Page.WaitForSelectorOptions()
                            .setTimeout(30_000)
                            .setState(WaitForSelectorState.VISIBLE)
            );
            System.out.println("Кнопка Google доступна ✅");

            // --- СНАЧАЛА ЖМЁМ GOOGLE (ВЫБОР СПОСОБА) ---
            System.out.println("Выбираем способ регистрации через Google...");
            Locator googleOption = page.locator("div.c-registration__social-inner[name='google']");
            if (googleOption.count() == 0 || !googleOption.first().isVisible()) {
                throw new RuntimeException("Элемент Google-соцрегистрации не найден.");
            }
            googleOption.first().click();
            pauseShort();

            // --- ЖМЁМ 'ЗАРЕГИСТРИРОВАТЬСЯ' И ЖДЁМ ОДИН ИЗ СЦЕНАРИЕВ ---
            System.out.println("Нажимаем 'Зарегистрироваться' (через JS) и ждём: Google / окно с логином и паролем / 'Личный кабинет' (до 5 минут)...");

            Locator regBtn = page.locator("div.c-registration__button.submit_registration:has-text('Зарегистрироваться')");
            if (regBtn.count() == 0 || !regBtn.first().isVisible()) {
                throw new RuntimeException("Кнопка 'Зарегистрироваться' для соц.регистрации не найдена.");
            }

            // жмём через JS по конкретной кнопке
            page.evaluate("el => el.click()", regBtn.first().elementHandle());

            long waitStart = System.currentTimeMillis();
            long timeoutMs = GOOGLE_FLOW_MAX_WAIT_MS;
            long lastLog = waitStart;

            boolean googleDetected = false;
            boolean postRegDetected = false;
            boolean lkDetected = false;

            while (System.currentTimeMillis() - waitStart < timeoutMs) {
                String url = "";
                try {
                    url = page.url();
                } catch (Exception ignored) {}

                // вариант 1: редирект на Google
                boolean urlLooksLikeGoogle =
                        url.contains("accounts.google.com")
                                || url.contains("consent.google.com")
                                || url.contains("myaccount.google.com")
                                || url.contains("://accounts.google.")
                                || url.contains("://www.google.");

                boolean emailFieldVisible = false;
                try {
                    Locator emailInput = page.locator("input[type='email']");
                    emailFieldVisible = emailInput.count() > 0 && emailInput.first().isVisible();
                } catch (Exception ignored) {}

                if (urlLooksLikeGoogle || emailFieldVisible) {
                    googleDetected = true;
                    System.out.println("Детектирован редирект на Google / форма логина Google ✅ (URL: " + url + ")");
                    break;
                }

                // вариант 2: появилось окно с логином/паролем 1xBet (как после обычной регистрации)
                Locator idLocCheck = page.locator("p#account-info-id");
                Locator passLocCheck = page.locator("p#account-info-password");
                if (idLocCheck.count() > 0 && idLocCheck.first().isVisible()
                        && passLocCheck.count() > 0 && passLocCheck.first().isVisible()) {
                    postRegDetected = true;
                    System.out.println("Обнаружено окно с логином и паролем 1xBet после соц-регистрации ✅");
                    break;
                }

                // вариант 3: нас просто сразу авторизовало (виден 'Личный кабинет')
                Locator lkBtnCheck = page.locator("a.header-lk-box-link[title='Личный кабинет']");
                if (lkBtnCheck.count() > 0 && lkBtnCheck.first().isVisible()) {
                    lkDetected = true;
                    System.out.println("Обнаружен 'Личный кабинет' — прямая авторизация без окна логин/пароль ✅");
                    break;
                }

                long now = System.currentTimeMillis();
                if (now - lastLog >= 10_000) {
                    System.out.println("Ждём решение капчи / один из сценариев... прошло " +
                            ((now - waitStart) / 1000) + " сек. (URL: " + url + ")");
                    lastLog = now;
                }

                page.waitForTimeout(500);
            }

            if (!googleDetected && !postRegDetected && !lkDetected) {
                throw new RuntimeException("За 5 минут не дождались ни Google, ни окна с логином/паролем, ни 'Личного кабинета'. " +
                        "Возможно, капча не решена или флоу завис.");
            }

            // --- ЕСЛИ БЫЛ GOOGLE — ПРОХОДИМ АВТОРИЗАЦИЮ И ЖДЁМ ВОЗВРАТ НА 1XBET ---
            if (googleDetected) {
                System.out.println("Запускаем авторизацию в Google в этой же вкладке...");
                performGoogleLogin(page, googleEmail, googlePassword);
                System.out.println("Ждём, пока после Google вернёмся на 1xBet...");
                page.waitForLoadState();
                pauseMedium();
                closeAllKnownPopups(page, "После возврата с Google");
            }

            // После Google или прямой регистрации пробуем поймать окно с логином/паролем
            Locator idLoc = page.locator("p#account-info-id");
            Locator passLoc = page.locator("p#account-info-password");
            boolean credsWindowVisible =
                    idLoc.count() > 0 && idLoc.first().isVisible()
                            && passLoc.count() > 0 && passLoc.first().isVisible();

            if (credsWindowVisible) {
                System.out.println("Окно с логином и паролем 1xBet активно — выполняем сценарий 'Копировать / SMS / файл / картинка / e-mail'...");

                String idValue = idLoc.first().innerText().trim();
                String passValue = passLoc.first().innerText().trim();
                System.out.println("Логин: " + idValue + ", Пароль: " + passValue);

                // сохраняем креды для отчёта
                sentLogin = idValue;
                sentPassword = passValue;

                // Копировать
                System.out.println("Жмём 'Копировать логин и пароль'...");
                clickIfVisible(page, "div#js-post-reg-copy-login-password");
                System.out.println("Подтверждаем всплывающее сообщение 'ОК' после копирования...");
                clickIfVisible(page, "button.swal2-confirm.swal2-styled:has-text('ОК')");
                pauseMedium();

                // Получить по SMS
                System.out.println("Жмём 'Получить по SMS'...");
                clickIfVisible(page, "button#account-info-button-sms");
                pauseMedium();
                closeAllKnownPopups(page, "После 'Получить по SMS' (соцрег)");

                // Сохранить в файл
                System.out.println("Жмём 'Сохранить в файл'...");
                clickIfVisible(page, "a#account-info-button-file");
                pauseMedium();
                closeAllKnownPopups(page, "После 'Сохранить в файл' (соцрег)");

                // Сохранить картинкой
                System.out.println("Жмём 'Сохранить картинкой'...");
                clickIfVisible(page, "a#account-info-button-image");
                pauseMedium();
                closeAllKnownPopups(page, "После 'Сохранить картинкой' (соцрег)");

                // Выслать на e-mail
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
                closeAllKnownPopups(page, "После 'Выслать на e-mail' (соцрег)");

            } else {
                System.out.println("Окно логин/пароль после соц-регистрации не появилось — пытаемся вытащить креды из текста и идём в 'Личный кабинет'.");
                Map<String, String> credsFromText = extractCredentials(page);
                if (credsFromText.get("login") != null) sentLogin = credsFromText.get("login");
                if (credsFromText.get("password") != null) sentPassword = credsFromText.get("password");
            }

            // --- КЛИКАЕМ НА БАННЕР 'ПОЛУЧИТЬ БОНУС' ---
            System.out.println("Кликаем по баннеру 'Получить бонус' (если есть)...");
            clickIfVisible(page, "span#form_get_bonus_after_submit");
            pauseMedium();
            closeAllKnownPopups(page, "После 'Получить бонус'");

            // --- ПЕРЕХОД В ЛИЧНЫЙ КАБИНЕТ ЧЕРЕЗ КНОПКУ В ШАПКЕ ---
            System.out.println("Переходим в Личный кабинет через кнопку в шапке...");
            clickIfVisible(page, "a.header-lk-box-link[title='Личный кабинет']");

            page.waitForLoadState();
            System.out.println("Страница Личного кабинета загружена.");
            closeAllKnownPopups(page, "Личный кабинет после перехода");

            // пробуем вытащить ID из ЛК (резервно)
            accountId = tryExtractAccountId(page);
            if (accountId != null) {
                System.out.println("Распознан ID аккаунта: " + accountId);
            } else {
                System.out.println("ID аккаунта в ЛК парсером не найден (это не ошибка, просто инфо).");
            }

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

            // --- ФИНАЛЬНЫЙ ОТЧЁТ В TELEGRAM ---
            long durationSec = (System.currentTimeMillis() - startMs) / 1000;
            StringBuilder sb = new StringBuilder();
            sb.append("✅ *Тест завершён успешно:* ").append(testName).append("\n")
                    .append("• Регистрация: через Google (соцсети)\n")
                    .append("• Google email: `").append(googleEmail).append("`\n");
            if (accountId != null) {
                sb.append("• ID (если распознан): `").append(accountId).append("`\n");
            }
            if (sentLogin != null || sentPassword != null) {
                sb.append("🔑 Данные аккаунта:\n");
                if (sentLogin != null) {
                    sb.append("• Логин: `").append(sentLogin).append("`\n");
                }
                if (sentPassword != null) {
                    sb.append("• Пароль: `").append(sentPassword).append("`\n");
                }
            }
            sb.append("🕒 Старт: ").append(startedAt).append("\n")
                    .append("⏱ Длительность: ").append(durationSec).append(" сек.\n")
                    .append("🌐 [1xbet.kz](https://1xbet.kz)");

            tg.sendMessage(sb.toString());

            System.out.println("=== ТЕСТ УСПЕШНО ЗАВЕРШЁН за " + durationSec + " сек. ===");

        } catch (Exception e) {
            System.out.println("❌ Ошибка во время выполнения теста: " + e);
            String screenshotPath = ScreenshotHelper.takeScreenshot(page, "v2_social_registration_google");
            System.out.println("Скриншот сохранён по пути: " + screenshotPath);
            tg.sendMessage("🚨 Ошибка в " + testName + ": " + e.getMessage());
            if (screenshotPath != null) {
                tg.sendPhoto(screenshotPath, "Скриншот ошибки");
            }
            throw e;
        }
    }
}
