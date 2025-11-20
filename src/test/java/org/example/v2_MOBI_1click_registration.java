package org.example;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.*;
import org.junit.jupiter.api.*;

import java.awt.*;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Properties;
import java.util.Random;

public class v2_MOBI_1click_registration {
    static Playwright playwright;
    static Browser browser;
    static BrowserContext context;
    static Page page;
    static Properties creds = new Properties();

    // --- Вспомогательные методы ---

    /**
     * Ждём, пока document.readyState станет "complete".
     * Если за maxWaitMs не стало — перезагружаем страницу.
     * Перезагрузок не больше 3, чтобы не зависнуть навсегда.
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
        for (int i = 0; i < 8; i++) code.append(chars.charAt(rand.nextInt(chars.length())));
        return code.toString();
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
                        .setSlowMo(150) // можно уменьшить/убрать, если надо быстрее
                        .setArgs(List.of(
                                "--start-maximized",
                                "--window-size=" + width + "," + height
                        ))
        );

        context = browser.newContext(
                new Browser.NewContextOptions()
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
    void registration1ClickFullFlow() throws InterruptedException {
        long start = System.currentTimeMillis();
        String botToken = creds.getProperty("telegram.bot.token");
        String chatId = creds.getProperty("telegram.chat.id");

        Telegram.send("🚀 *Тест v2_MOBI_1click_registration* стартовал\n(Регистрация 'В 1 клик')", botToken, chatId);

        try {
            System.out.println("Открываем сайт (мобильная версия)...");
            page.navigate("https://1xbet.kz/?platform_type=mobile");
            page.waitForLoadState(LoadState.DOMCONTENTLOADED);
            waitForPageOrReload(15_000);
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
            page.waitForSelector(".multiselect__option .c-registration-select--refuse-bonuses");
            page.click(".multiselect__option .c-registration-select--refuse-bonuses:has-text('Отказ от бонусов')");
            page.waitForTimeout(500);

            page.click("div.c-registration__block--bonus .multiselect__select");
            page.waitForSelector(".multiselect__option .c-registration-select--sport-bonus");
            page.click(".multiselect__option .c-registration-select--sport-bonus:has-text('Получать бонусы')");
            page.waitForTimeout(500);

            System.out.println("Жмём 'Зарегистрироваться'");
            page.click("div.submit_registration");

            // ---- ЖДЁМ РЕШЕНИЯ КАПЧИ И ПОЯВЛЕНИЯ БЛОКА С ЛОГИНОМ/ПАРОЛЕМ ----
            System.out.println("Теперь решай капчу вручную — я жду появление блока с кнопкой 'Копировать' (до 10 минут)...");
            try {
                page.waitForSelector(
                        "div#js-post-reg-copy-login-password",
                        new Page.WaitForSelectorOptions()
                                .setTimeout(600_000) // максимум 10 минут
                                .setState(WaitForSelectorState.VISIBLE)
                );
                System.out.println("Блок с 'Копировать' появился ✅");
            } catch (PlaywrightException e) {
                throw new RuntimeException("Блок с 'Копировать' не появился — капча не решена или что-то пошло не так!");
            }

            System.out.println("Нажимаем 'Копировать' логин/пароль");
            page.click("div#js-post-reg-copy-login-password");
            page.waitForTimeout(500);

            page.waitForSelector("button.swal2-confirm.swal2-styled");
            page.click("button.swal2-confirm.swal2-styled");
            page.waitForTimeout(500);

            System.out.println("Высылаем данные по SMS");
            page.waitForSelector("button#account-info-button-sms");
            page.click("button#account-info-button-sms");
            page.waitForTimeout(500);
            closeIfVisible("button.reset-password__close", "reset-password__close");

            System.out.println("Сохраняем в файл");
            page.waitForSelector("a#account-info-button-file");
            page.click("a#account-info-button-file");
            page.waitForTimeout(500);

            System.out.println("Сохраняем картинкой");
            page.waitForSelector("a#account-info-button-image");
            page.click("a#account-info-button-image");
            page.waitForTimeout(500);

            System.out.println("Высылаем на e-mail");
            page.waitForSelector("a#form_mail_after_submit");
            page.click("a#form_mail_after_submit");
            page.waitForTimeout(500);

            page.waitForSelector("input.js-post-email-content-form__input");
            page.fill("input.js-post-email-content-form__input", creds.getProperty("registration.email"));
            page.waitForSelector("button.js-post-email-content-form__btn:not([disabled])");
            page.click("button.js-post-email-content-form__btn:not([disabled])");
            page.waitForTimeout(500);

            System.out.println("Закрываем попап регистрации крестиком");
            closeIfVisible("button.popup-registration__close", "popup-registration__close");
            page.waitForTimeout(500);

            System.out.println("Открываем меню (ЛК)");
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
            String summary = "✅ *Тест v2_MOBI_1click_registration завершён успешно*\n"
                    + "• Регистрация 'В 1 клик' — выполнена\n"
                    + "• Выход — произведён\n"
                    + "🕒 Время выполнения: *" + duration + " сек.*\n"
                    + "🌐 [1xbet.kz](https://1xbet.kz)\n"
                    + "_Браузер остаётся открытым._";

            System.out.println(summary);
            Telegram.send(summary, botToken, chatId);

        } catch (Exception e) {
            String err = "❌ *Тест v2_MOBI_1click_registration упал*\n"
                    + "Сообщение: `" + (e.getMessage() == null ? "null" : e.getMessage().replace("_", "\\_")) + "`";
            System.out.println(err);
            Telegram.send(err, botToken, chatId);
            throw new RuntimeException(e);
        }
    }

    @AfterAll
    static void tearDownAll() {
        System.out.println("Тест завершён ✅ (браузер остаётся открытым)");
    }

    // --- Telegram helper ---
    static class Telegram {
        static void send(String text, String botToken, String chatId) {
            try {
                String url = "https://api.telegram.org/bot" + botToken + "/sendMessage";
                String data = "chat_id=" + chatId
                        + "&text=" + java.net.URLEncoder.encode(text, "UTF-8")
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
