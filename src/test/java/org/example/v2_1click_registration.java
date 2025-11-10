package org.example;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitForSelectorState;
import com.microsoft.playwright.TimeoutError;
import org.junit.jupiter.api.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class v2_1click_registration {
    static Playwright playwright;
    static Browser browser;
    static BrowserContext context;
    static Page page;
    static TelegramNotifier tg;
    static final Path MESSAGES_SESSION = Paths.get("messages-session.json");

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

        String botToken = ConfigHelper.get("telegram.bot.token");
        String chatId = ConfigHelper.get("telegram.chat.id");
        tg = new TelegramNotifier(botToken, chatId);
    }

    @AfterAll
    static void tearDownAll() {
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

    static void waitForRegistrationModal(Page page) {
        String[] sels = {
                "div#games_content.c-registration",
                "div.arcticmodal-container div.c-registration"
        };
        page.waitForSelector(String.join(", ", sels),
                new Page.WaitForSelectorOptions().setTimeout(30_000).setState(WaitForSelectorState.VISIBLE));
    }

    static void clickAllOneClickTabs(Page page) {
        Locator allTabs = page.locator("button:has-text('В 1 клик')");
        int count = allTabs.count();
        for (int i = 0; i < count; i++) {
            Locator tab = allTabs.nth(i);
            if (!tab.isVisible()) continue;
            try {
                tab.click(new Locator.ClickOptions().setTimeout(2000));
            } catch (Exception e1) {
                try { page.evaluate("el => el.click()", tab.elementHandle()); }
                catch (Exception e2) {
                    try { tab.click(new Locator.ClickOptions().setForce(true)); }
                    catch (Exception ignored) {}
                }
            }
            pauseShort();
        }
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

    // --- Google Messages ---
    static String fetchSmsCodeFromGoogleMessages() {
        BrowserContext messagesContext = browser.newContext(
                new Browser.NewContextOptions().setStorageStatePath(MESSAGES_SESSION)
        );
        Page messagesPage = messagesContext.newPage();
        messagesPage.setDefaultTimeout(20_000);
        messagesPage.navigate("https://messages.google.com/web/conversations");

        for (int i = 0; i < 20; i++) {
            if (messagesPage.locator("mws-conversation-list-item").count() > 0) break;
            messagesPage.waitForTimeout(1000);
        }

        Locator chat = messagesPage.locator("mws-conversation-list-item").first();
        chat.click();
        messagesPage.waitForTimeout(1200);

        Locator nodes = messagesPage.locator("div.text-msg.msg-content div.ng-star-inserted");
        int count = nodes.count();
        String text = count > 0 ? nodes.nth(count - 1).innerText() : "";
        Matcher m = Pattern.compile("(?<!\\d)(\\d{4,8})(?!\\d)").matcher(text);
        String code = m.find() ? m.group(1) : null;
        messagesContext.close();
        if (code == null || code.isBlank())
            throw new RuntimeException("Код из SMS не найден");
        return code;
    }

    static Map<String, String> extractCredentials(Page page) {
        String login = null, password = null;
        String txt = page.innerText("body");
        Matcher ml = Pattern.compile("Логин\\s*[:\\-]?\\s*(\\S+)", Pattern.CASE_INSENSITIVE).matcher(txt);
        if (ml.find()) login = ml.group(1);
        Matcher mp = Pattern.compile("Пароль\\s*[:\\-]?\\s*(\\S+)", Pattern.CASE_INSENSITIVE).matcher(txt);
        if (mp.find()) password = mp.group(1);
        Map<String, String> out = new HashMap<>();
        out.put("login", login);
        out.put("password", password);
        return out;
    }

    static void tryBindBySmsIfModalVisible(Page page) {
        Locator field = page.locator("input.phone-sms-modal-content__code").first();
        if (field == null || field.count() == 0 || !field.isVisible()) return;
        String code = fetchSmsCodeFromGoogleMessages();
        field.fill(code);
        Locator confirmBtn = page.locator("button.phone-sms-modal-content__send:has-text('Подтвердить')");
        if (confirmBtn.count() > 0 && confirmBtn.first().isVisible()) {
            confirmBtn.first().click();
            tg.sendMessage("🔐 Привязка по SMS подтверждена кодом: `" + code + "`");
        }
    }

    @Test
    void v2_registration() throws Exception {
        long startTime = System.currentTimeMillis();
        tg.sendMessage("🚀 *Тест v2_1click_registration* стартовал");

        String sentLogin = null;
        String sentPassword = null;

        try {
            page.navigate("https://1xbet.kz/?platform_type=desktop");
            pauseMedium();
            waitAndClick(page, "button#registration-form-call", 15_000);
            waitForRegistrationModal(page);
            clickAllOneClickTabs(page);

            page.waitForSelector(
                    "div#games_content.c-registration button.c-registration__tab.active:has-text('В 1 клик')",
                    new Page.WaitForSelectorOptions().setTimeout(120_000).setState(WaitForSelectorState.VISIBLE)
            );

            String promo = randomPromo(8);
            Locator promoInput = page.locator("input#popup_registration_ref_code");
            if (promoInput.count() > 0 && promoInput.first().isVisible())
                promoInput.first().fill(promo);

            clickIfVisible(page, "div.c-registration-bonus__item.c-registration-bonus__item--close");
            clickIfVisible(page, "div.c-registration-bonus__item:has(.c-registration-bonus__title:has-text('Принять'))");

            page.waitForFunction(
                    "document.querySelector('div.c-registration__button.submit_registration') && " +
                            "!document.querySelector('div.c-registration__button.submit_registration').classList.contains('disabled')"
            );

            try {
                page.locator("div.c-registration__button.submit_registration:has-text('Зарегистрироваться')").first().click();
            } catch (Exception e) {
                page.evaluate("document.querySelector('div.c-registration__button.submit_registration')?.click()");
            }

            page.waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(60_000));

            // --- ИЗВЛЕКАЕМ КРЕДЫ ---
            String login = page.locator("p#account-info-id").innerText().trim();
            String password = page.locator("p#account-info-password").innerText().trim();
            System.out.println("Логин: " + login + ", Пароль: " + password);
            tg.sendMessage("🔑 Регистрация завершена\nЛогин: `" + login + "`\nПароль: `" + password + "`");

// --- КОПИРУЕМ КРЕДЫ ---
            clickIfVisible(page, "div#js-post-reg-copy-login-password");
            clickIfVisible(page, "button.swal2-confirm.swal2-styled:has-text('ОК')");
            pauseMedium();

// --- ПОЛУЧИТЬ ПО SMS ---
            clickIfVisible(page, "button#account-info-button-sms");
            pauseMedium();

// --- ЗАКРЫВАЕМ ВСЕ ВСПЛЫВАЮЩИЕ ОКНА ---
            clickIfVisible(page, "button.reset-password__close");
            clickIfVisible(page, "button.identification-popup-close.identification-popup-binding__close");

// --- СОХРАНИТЬ В ФАЙЛ ---
            clickIfVisible(page, "a#account-info-button-file");
            pauseMedium();

// --- ЗАКРЫВАЕМ ОКНО ИДЕНТИФИКАЦИИ ---
            clickIfVisible(page, "button.identification-popup-close.identification-popup-get-bonus__close");

// --- СОХРАНИТЬ КАРТИНКОЙ ---
            clickIfVisible(page, "a#account-info-button-image");
            pauseMedium();

// --- СНОВА ЗАКРЫВАЕМ ОКНО ИДЕНТИФИКАЦИИ ---
            clickIfVisible(page, "button.identification-popup-close.identification-popup-get-bonus__close");

// --- ВЫСЛАТЬ НА EMAIL ---
            clickIfVisible(page, "a#form_mail_after_submit");
            Locator emailInput = page.locator("input.js-post-email-content-form__input[type='email']");
            if (emailInput.count() > 0 && emailInput.first().isVisible()) {
                emailInput.first().fill(ConfigHelper.get("email"));
                pauseShort();
                Locator sendBtn = page.locator("button.js-post-email-content-form__btn:not([disabled])");
                if (sendBtn.count() > 0) {
                    sendBtn.first().click();
                    System.out.println("Email отправлен ✅");
                }
            }
            pauseMedium();

// --- ЗАКРЫВАЕМ ЕЩЁ РАЗ ОКНО ---
            clickIfVisible(page, "button.identification-popup-close.identification-popup-get-bonus__close");

// --- КЛИКАЕМ НА БАННЕР 'ПОЛУЧИТЬ БОНУС' ---
            clickIfVisible(page, "span#form_get_bonus_after_submit");

// --- ПЕРЕХОД В ЛИЧНЫЙ КАБИНЕТ ---
            page.navigate("https://1xbet.kz/office/account");
            page.waitForLoadState(LoadState.DOMCONTENTLOADED);

// --- ЗАКРЫВАЕМ МОДАЛКУ ---
            clickIfVisible(page, "button.identification-popup-close.identification-popup-transition__close");

// --- ВЫХОД ---
            Locator logoutBtn = page.locator("a.ap-left-nav__item.ap-left-nav__item_exit:has-text('Выход')");
            if (logoutBtn.count() > 0 && logoutBtn.first().isVisible()) {
                logoutBtn.first().click();
                clickIfVisible(page, "button.swal2-confirm.swal2-styled:has-text('ОК')");
            }

            System.out.println("Выход из аккаунта завершён ✅");
            tg.sendMessage("✅ Регистрация и выход завершены.\nЛогин: `" + login + "`\nПароль: `" + password + "`");

            boolean loggedOut = page.locator("button#registration-form-call").isVisible();
            assertTrue(loggedOut, "Ожидали гостевое состояние после выхода.");

            long duration = (System.currentTimeMillis() - startTime) / 1000;
            tg.sendMessage("✅ *Тест завершён успешно:* v2_1click_registration\n" +
                    "• Регистрация и выход — ок\n" +
                    "• Логин: `" + sentLogin + "`\n" +
                    "• Пароль: `" + sentPassword + "`\n" +
                    "🕒 " + duration + " сек.\n🌐 [1xbet.kz](https://1xbet.kz)");

        } catch (Exception e) {
            String screenshotPath = ScreenshotHelper.takeScreenshot(page, "v2_1click_registration");
            tg.sendMessage("🚨 Ошибка: " + e.getMessage());
            if (screenshotPath != null) tg.sendPhoto(screenshotPath, "Скриншот ошибки");
            throw e;
        }
    }
}
