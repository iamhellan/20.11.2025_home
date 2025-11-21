package org.example;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitForSelectorState;
import org.junit.jupiter.api.*;

import java.awt.*;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class v2_MOBI_1click_registration {
    static Playwright playwright;
    static Browser browser;
    static BrowserContext context;
    static Page page;
    static Properties creds = new Properties();

    // --- селекторы крестиков / попапов (без закрытия основной рег-модалки!) ---
    static final String[] POPUP_CLOSE_SELECTORS = new String[]{
            // крестики НЕ регистрационного окна
            "div.box-modal_close.arcticmodal-close",
            ".arcticmodal-close",
            "div.box-modal_close",

            // ВАЖНО: НЕ трогаем здесь button[title='Закрыть'] и popup-registration__close,
            // чтобы не закрывать окно с логином/паролем раньше времени.

            // идентификация / привязка / бонусы
            "button.identification-popup-close.identification-popup-binding__close",
            "button.identification-popup-close.identification-popup-get-bonus__close",
            "button.identification-popup-close.identification-popup-transition__close",

            // восстановление пароля
            "button.reset-password__close",

            // Vue UI
            "button.v--modal-close-btn",

            // универсальные варианты (если они вдруг не попадают на основное окно регистрации)
            ".popup__close",
            ".modal__close"
    };

    // --- Вспомогательные методы ---

    /**
     * Ждём, пока document.readyState станет "complete".
     * Если за maxWaitMs не стало — перезагружаем страницу.
     * Перезагрузок не больше 3.
     */
    static void waitForPageOrReload(int maxWaitMs) {
        int waited = 0;
        int reloads = 0;

        while (true) {
            try {
                String readyState = (String) page.evaluate("() => document.readyState");
                if ("complete".equals(readyState)) {
                    System.out.println("document.readyState=complete");
                    break;
                }
            } catch (Exception e) {
                System.out.println("Ошибка при проверке readyState: " + e.getMessage());
            }

            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {
            }
            waited += 500;

            if (waited >= maxWaitMs) {
                if (reloads >= 3) {
                    System.out.println("⛔ Страница не загрузилась после " + (reloads + 1) + " попыток, прекращаем обновлять");
                    break;
                }
                System.out.println("Страница не загрузилась за " + maxWaitMs + " мс, обновляем! Попытка #" + (reloads + 1));
                page.reload();
                waited = 0;
                reloads++;
            }
        }
    }

    static void closeIfVisible(String selector, String description) {
        try {
            Locator popup = page.locator(selector);
            popup.waitFor(
                    new Locator.WaitForOptions()
                            .setTimeout(2000)
                            .setState(WaitForSelectorState.ATTACHED)
            );
            if (popup.isVisible()) {
                System.out.println("Закрываем: " + description);
                popup.click();
                page.waitForTimeout(500);
            } else {
                System.out.println("Элемент " + description + " не виден — пропускаем");
            }
        } catch (Exception e) {
            System.out.println("Элемент " + description + " не найден — пропускаем");
        }
    }

    static String generatePromoCode() {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        Random rand = new Random();
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            code.append(chars.charAt(rand.nextInt(chars.length())));
        }
        return code.toString();
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

    static void closeAllKnownPopups(Page page, String contextLabel) {
        System.out.println("Пробуем закрыть всплывающие окна (JS-only). Контекст: " + contextLabel);
        try {
            // закрываем все известные крестики/кнопки через JS-клик,
            // overlay и контейнеры здесь уже не участвуют
            page.evaluate("selectors => {" +
                    "  try {" +
                    "    for (const sel of selectors) {" +
                    "      try {" +
                    "        const nodes = document.querySelectorAll(sel);" +
                    "        if (!nodes || !nodes.length) continue;" +
                    "        nodes.forEach(el => {" +
                    "          try { el.click(); } catch(e) {}" +
                    "        });" +
                    "      } catch(e) {}" +
                    "    }" +
                    "  } catch(e) {}" +
                    "}", (Object) POPUP_CLOSE_SELECTORS);
        } catch (Exception e) {
            System.out.println("Ошибка при JS-закрытии попапов: " + e.getMessage());
        }

        // дополнительно глушим overlay и прочий мусор, чтобы он не ловил клики дальше
        try {
            neutralizeOverlayIfNeeded(page);
        } catch (Exception e) {
            System.out.println("Ошибка при нейтрализации оверлеев: " + e.getMessage());
        }

        page.waitForTimeout(300);
        System.out.println("Завершили JS-попытки закрытия попапов. Контекст: " + contextLabel);
    }

    static Map<String, String> extractCredentialsFromPage(Page page) {
        System.out.println("Пробуем извлечь логин/пароль из страницы...");
        Map<String, String> result = new HashMap<>();
        String login = null;
        String password = null;

        try {
            // 1) по явным селекторам
            try {
                Locator idLoc = page.locator("p#account-info-id");
                Locator passLoc = page.locator("p#account-info-password");
                if (idLoc.count() > 0 && idLoc.first().isVisible()) {
                    login = idLoc.first().innerText().trim();
                }
                if (passLoc.count() > 0 && passLoc.first().isVisible()) {
                    password = passLoc.first().innerText().trim();
                }
            } catch (Exception ignored) {
            }

            // 2) если не нашли — по тексту body
            if (login == null || password == null) {
                String body = page.innerText("body");
                Matcher ml = Pattern.compile("Логин\\s*[:\\-]?\\s*(\\S+)", Pattern.CASE_INSENSITIVE).matcher(body);
                if (ml.find()) login = ml.group(1);
                Matcher mp = Pattern.compile("Пароль\\s*[:\\-]?\\s*(\\S+)", Pattern.CASE_INSENSITIVE).matcher(body);
                if (mp.find()) password = mp.group(1);
            }
        } catch (Exception e) {
            System.out.println("Ошибка при извлечении кредов: " + e.getMessage());
        }

        result.put("login", login);
        result.put("password", password);
        System.out.println("Креды: login=" + login + ", password=" + password);
        return result;
    }

    @BeforeAll
    static void setUpAll() throws IOException {
        // креды
        creds.load(new FileInputStream("src/test/resources/config.properties"));

        playwright = Playwright.create();

        // --- полноэкранный мобильный браузер ---
        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        int width = (int) screenSize.getWidth();
        int height = (int) screenSize.getHeight();

        browser = playwright.chromium().launch(
                new BrowserType.LaunchOptions()
                        .setHeadless(false)
                        .setSlowMo(150)
                        .setArgs(List.of(
                                "--start-maximized",
                                "--window-size=" + width + "," + height
                        ))
        );

        // главное отличие от исходника: setAcceptDownloads(true)
        context = browser.newContext(
                new Browser.NewContextOptions()
                        .setAcceptDownloads(true)
                        .setViewportSize(null)
                        .setUserAgent(
                                "Mozilla/5.0 (Linux; Android 11; SM-G998B) " +
                                        "AppleWebKit/537.36 (KHTML, like Gecko) " +
                                        "Chrome/95.0.4638.74 Mobile Safari/537.36"
                        )
        );

        page = context.newPage();
        page.setDefaultTimeout(30_000);
    }

    @Test
    void registration1ClickFullFlow() {
        long start = System.currentTimeMillis();
        String botToken = creds.getProperty("telegram.bot.token");
        String chatId = creds.getProperty("telegram.chat.id");

        String accountLogin = null;
        String accountPassword = null;

        Telegram.send("🚀 *Тест v2_MOBI_1click_registration* стартовал\n(Регистрация 'В 1 клик')", botToken, chatId);

        try {
            System.out.println("Открываем сайт (мобильная версия)...");
            page.navigate("https://1xbet.kz/?platform_type=mobile");
            page.waitForLoadState(LoadState.DOMCONTENTLOADED);
            waitForPageOrReload(15_000);
            closeAllKnownPopups(page, "После открытия сайта");
            page.waitForTimeout(1000);

            System.out.println("Кликаем 'Регистрация'");
            page.waitForSelector("button.header-btn--registration");
            page.click("button.header-btn--registration");
            page.waitForTimeout(1000);

            System.out.println("Выбираем вкладку 'В 1 клик'");
            page.waitForSelector("button.c-registration__tab:has-text('В 1 клик')");
            page.click("button.c-registration__tab:has-text('В 1 клик')");
            page.waitForTimeout(1000);

            String promoCode = generatePromoCode();
            System.out.println("Генерируем промокод: " + promoCode);
            page.fill("input#registration_ref_code", promoCode);
            page.waitForTimeout(1000);

            System.out.println("Отказываемся от бонусов → выбираем бонус снова");
            page.click("div.c-registration__block--bonus .multiselect__select");
            page.waitForSelector(".multiselect__option .c-registration-select--refuse-bonuses:has-text('Отказ от бонусов')");
            page.click(".multiselect__option .c-registration-select--refuse-bonuses:has-text('Отказ от бонусов')");
            page.waitForTimeout(500);

            page.click("div.c-registration__block--bonus .multiselect__select");
            page.waitForSelector(".multiselect__option .c-registration-select--sport-bonus:has-text('Получать бонусы')");
            page.click(".multiselect__option .c-registration-select--sport-bonus:has-text('Получать бонусы')");
            page.waitForTimeout(500);

            System.out.println("Жмём 'Зарегистрироваться'");
            page.click("div.submit_registration");

            // ---- ЖДЁМ КАПЧУ И БЛОК С КРЕДАМИ ----
            System.out.println("Теперь решай капчу вручную — жду блок с 'Копировать' (до 10 минут)...");
            try {
                page.waitForSelector(
                        "div#js-post-reg-copy-login-password",
                        new Page.WaitForSelectorOptions()
                                .setTimeout(600_000)
                                .setState(WaitForSelectorState.VISIBLE)
                );
                System.out.println("Блок с 'Копировать' появился ✅");
            } catch (PlaywrightException e) {
                throw new RuntimeException("Блок с 'Копировать' не появился — капча не решена или что-то пошло не так!");
            }

            // креды
            Map<String, String> credsMap = extractCredentialsFromPage(page);
            accountLogin = credsMap.get("login");
            accountPassword = credsMap.get("password");

            System.out.println("Нажимаем 'Копировать' логин/пароль");
            page.click("div#js-post-reg-copy-login-password");
            page.waitForTimeout(500);

            page.waitForSelector("button.swal2-confirm.swal2-styled");
            page.click("button.swal2-confirm.swal2-styled");
            page.waitForTimeout(500);

            page.waitForSelector("button#account-info-button-sms");
            page.click("button#account-info-button-sms");
            page.waitForTimeout(500);
            closeAllKnownPopups(page, "После 'Выслать по SMS'");

            System.out.println("Сохраняем в файл");
            // здесь уже используется Chromium + acceptDownloads, системное окно не надо закрывать
            page.waitForSelector("a#account-info-button-file");
            page.click("a#account-info-button-file");
            page.waitForTimeout(500);
            closeAllKnownPopups(page, "После 'Сохранить в файл'");

            System.out.println("Сохраняем картинкой");
            page.waitForSelector("a#account-info-button-image");
            page.click("a#account-info-button-image");
            page.waitForTimeout(500);
            closeAllKnownPopups(page, "После 'Сохранить картинкой'");

            System.out.println("Высылаем на e-mail");
            page.waitForSelector("a#form_mail_after_submit");
            page.click("a#form_mail_after_submit");
            page.waitForTimeout(500);

            page.waitForSelector("input.js-post-email-content-form__input");
            page.fill("input.js-post-email-content-form__input", creds.getProperty("registration.email"));
            page.waitForSelector("button.js-post-email-content-form__btn:not([disabled])");
            page.click("button.js-post-email-content-form__btn:not([disabled])");
            page.waitForTimeout(500);
            closeAllKnownPopups(page, "После 'Выслать на e-mail'");

            System.out.println("Закрываем попап регистрации крестиком");
            closeIfVisible("button.popup-registration__close", "popup-registration__close");
            page.waitForTimeout(500);


            System.out.println("Открываем меню (ЛК)");
            closeAllKnownPopups(page, "Перед открытием меню ЛК");
            page.waitForSelector("button.user-header__link.header__reg_ico");
            page.click("button.user-header__link.header__reg_ico");
            page.waitForTimeout(1000);

            System.out.println("Выходим из аккаунта");
            page.waitForSelector("button.drop-menu-list__link_exit");
            page.click("button.drop-menu-list__link_exit");
            page.waitForTimeout(500);

            System.out.println("Подтверждаем выход (ОК)");
            page.waitForSelector("button.swal2-confirm.swal2-styled");
            page.click("button.swal2-confirm.swal2-styled");
            page.waitForTimeout(1000);

            long duration = (System.currentTimeMillis() - start) / 1000;
            StringBuilder summary = new StringBuilder();
            summary.append("✅ *Тест v2_MOBI_1click_registration завершён успешно*\n")
                    .append("• Регистрация 'В 1 клик' — выполнена\n")
                    .append("• Выход — произведён\n");
            if (accountLogin != null || accountPassword != null) {
                summary.append("🔑 Данные аккаунта:\n");
                if (accountLogin != null) {
                    summary.append("• Логин: `").append(accountLogin).append("`\n");
                }
                if (accountPassword != null) {
                    summary.append("• Пароль: `").append(accountPassword).append("`\n");
                }
            }
            summary.append("🕒 Время выполнения: *").append(duration).append(" сек.*\n")
                    .append("🌐 [1xbet.kz](https://1xbet.kz)\n")
                    .append("_Браузер остаётся открытым._");

            System.out.println(summary);
            Telegram.send(summary.toString(), botToken, chatId);

        } catch (Exception e) {
            String msg = e.getMessage();
            String safeMsg = (msg == null ? "null" : msg.replace("_", "\\_"));
            String err = "❌ *Тест v2_MOBI_1click_registration упал*\n"
                    + "Сообщение: `" + safeMsg + "`";
            System.out.println(err);
            Telegram.send(err, botToken, chatId);
            throw new RuntimeException(e);
        }
    }

    @AfterAll
    static void tearDownAll() {
        System.out.println("Тест завершён ✅ (браузер остаётся открытым)");
        // Если нужно закрывать — раскомментируй при необходимости:
        // if (browser != null) browser.close();
        // if (playwright != null) playwright.close();
    }

    // --- Telegram helper ---
    static class Telegram {
        static void send(String text, String botToken, String chatId) {
            try {
                String url = "https://api.telegram.org/bot" + botToken + "/sendMessage";
                String data = "chat_id=" + chatId
                        + "&text=" + URLEncoder.encode(text, StandardCharsets.UTF_8)
                        + "&parse_mode=Markdown";
                java.net.http.HttpClient.newHttpClient().send(
                        java.net.http.HttpRequest.newBuilder()
                                .uri(java.net.URI.create(url))
                                .header("Content-Type", "application/x-www-form-urlencoded")
                                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(data))
                                .build(),
                        java.net.http.HttpResponse.BodyHandlers.discarding()
                );
                System.out.println("📨 Сообщение отправлено в Telegram");
            } catch (Exception e) {
                System.out.println("⚠️ Ошибка Telegram: " + e.getMessage());
            }
        }
    }
}
