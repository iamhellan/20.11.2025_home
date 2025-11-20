package org.example;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.*;
import org.junit.jupiter.api.*;

import java.text.SimpleDateFormat;
import java.util.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.Path;
import java.awt.*; // для получения размера экрана
import java.util.List;

public class v2_MOBI_promo {
    static Playwright playwright;
    static Browser browser;
    static BrowserContext context;
    static Page mainPage;
    static TelegramNotifier tg;

    // ВАЖНО: поменяй путь под своего пользователя/проект
    private final String screenshotsFolder = "C:\\Users\\zhntm\\IdeaProjects\\11.11.2025\\1XBONUS\\Мобильная версия";
    private final List<String> promoNames = new ArrayList<>();

    @BeforeAll
    static void setUpAll() {
        playwright = Playwright.create();

        // --- Берём реальное разрешение экрана и создаём окно на весь экран ---
        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        int width = (int) screenSize.getWidth();
        int height = (int) screenSize.getHeight();

        List<String> args = List.of(
                "--start-maximized",
                "--window-size=" + width + "," + height
        );

        browser = playwright.chromium().launch(
                new BrowserType.LaunchOptions()
                        .setHeadless(false)
                        .setSlowMo(200) // 200 мс задержка между действиями
                        .setArgs(args)
        );

        context = browser.newContext(
                new Browser.NewContextOptions()
                        .setViewportSize(null) // используем размер окна браузера (во весь экран)
                        .setUserAgent("Mozilla/5.0 (Linux; Android 11; SM-G998B) " +
                                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                                "Chrome/95.0.4638.74 Mobile Safari/537.36")
        );

        mainPage = context.newPage();
        mainPage.setDefaultTimeout(30_000);

        // --- Telegram ---
        String botToken = ConfigHelper.get("telegram.bot.token");
        String chatId = ConfigHelper.get("telegram.chat.id");
        tg = new TelegramNotifier(botToken, chatId);
    }

    @Test
    void openMobilePromoAndTakeScreenshots() {
        long startTime = System.currentTimeMillis();

        // --- Telegram: старт ---
        tg.sendMessage(
                "📱 *Старт*: v2\\_MOBI\\_promo (мобильная версия)\n"
                        + "• Время: *" + new SimpleDateFormat("HH:mm:ss").format(new Date()) + "*\n"
                        + "• Сайт: [1xbet\\.kz](https://1xbet.kz/?platform_type=mobile)\n"
                        + "_Проверка акций и создание скриншотов для мобильной версии..._"
        );

        try {
            // --- Гарантируем, что папка под скрины существует ---
            ensureScreenshotsDir();

            // --- Переход на мобильный сайт ---
            mainPage.navigate("https://1xbet.kz/?platform_type=mobile");
            mainPage.waitForLoadState(LoadState.DOMCONTENTLOADED);
            mainPage.waitForTimeout(2000);

            // --- Открываем бургер ---
            System.out.println("Открываем бургер-меню...");
            mainPage.click("button.header__hamburger");
            mainPage.waitForTimeout(800);

            // --- Точный клик по стрелке рядом с "Акции & Promo" ---
            System.out.println("Пробуем кликнуть стрелку у 'Акции & Promo' (через JS с ожиданием)");
            try {
                mainPage.waitForSelector("div.drop-menu-list__arrow",
                        new Page.WaitForSelectorOptions().setTimeout(8000).setState(WaitForSelectorState.ATTACHED));

                mainPage.evaluate("""
                const items = Array.from(document.querySelectorAll('div.drop-menu-list__item'));
                const target = items.find(el => el.textContent.includes('Акции'));
                if (target) {
                    const arrow = target.querySelector('div.drop-menu-list__arrow');
                    if (arrow) {
                        const rect = arrow.getBoundingClientRect();
                        window.scrollTo(0, rect.top - 100);
                        arrow.click();
                    }
                }
                """);

                mainPage.waitForSelector("div.drop-menu-list_inner",
                        new Page.WaitForSelectorOptions().setTimeout(8000).setState(WaitForSelectorState.VISIBLE));

                System.out.println("✅ Стрелка 'Акции & Promo' нажата, меню раскрыто");
            } catch (Exception e) {
                System.out.println("⚠ Ошибка при клике на стрелку 'Акции & Promo': " + e.getMessage());
            }

            // --- Ждём блок акций ---
            Locator promoBlock = mainPage.locator("div.drop-menu-list_inner");
            promoBlock.waitFor(new Locator.WaitForOptions().setTimeout(8000));

            List<Locator> promoLinks = promoBlock.locator("a.drop-menu-list__link").all();
            System.out.println("Найдено акций: " + promoLinks.size());
            if (promoLinks.isEmpty()) throw new RuntimeException("❌ Акции не найдены");

            // --- Сохраняем имена акций ---
            for (Locator link : promoLinks) {
                try {
                    promoNames.add(link.innerText().trim());
                } catch (Exception ignored) {
                }
            }

            // --- Перебор акций ---
            int index = 1;
            for (Locator link : promoLinks) {
                String href = link.getAttribute("href");
                if (href == null || href.isBlank()) continue;

                // Базовый URL (обычно /ru/promotions/...)
                String baseUrl = href.startsWith("http") ? href : "https://1xbet.kz" + href;
                String promoName = index <= promoNames.size() ? promoNames.get(index - 1) : ("Акция #" + index);

                System.out.println("=== " + promoName + " → " + baseUrl);

                Page tab = context.newPage();

                // --- Каждую акцию открываем ПООЧЕРЁДНО на трёх языках через URL ---
                String[] langs = {"ru", "kz", "en"};
                for (String lang : langs) {
                    String langUrl = buildPromoUrlForLang(baseUrl, lang);
                    System.out.println(" → [" + lang + "] " + langUrl);

                    tab.navigate(langUrl);
                    waitForPageLoaded(tab, langUrl, index, lang);

                    takeScreenshot(tab, promoName, lang);
                }

                tab.close();
                mainPage.bringToFront();
                index++;
                mainPage.waitForTimeout(800);
            }

            // --- Telegram: завершение ---
            long elapsed = (System.currentTimeMillis() - startTime) / 1000;
            StringBuilder report = new StringBuilder();
            report.append("✅ *Завершено*: v2\\_MOBI\\_promo\n")
                    .append("• Проверено акций: *").append(promoNames.size()).append("*\n\n")
                    .append("📋 *Список акций:*\n");
            for (String name : promoNames) {
                report.append("• ").append(name.replace("-", "\\-")).append("\n");
            }
            report.append("\n📂 *Скриншоты сохранены в:*\n`")
                    .append(getEscapedScreenshotsFolder()).append("`\n")
                    .append("🕒 *Время выполнения:* ").append(elapsed).append(" сек.\n")
                    .append("🌐 [1xbet\\.kz](https://1xbet.kz/?platform_type=mobile)");

            tg.sendMessage(report.toString());

        } catch (Exception e) {
            tg.sendMessage("❌ *Ошибка в v2\\_MOBI\\_promo*: `" + e.getMessage().replace("_", "\\_") + "`");
            e.printStackTrace();
        }
    }

    /**
     * Формируем URL промо под конкретный язык.
     * Ожидаем базовый вид: https://1xbet.kz/ru/..., меняем сегмент /ru/ на /kz/ или /en/.
     */
    private String buildPromoUrlForLang(String baseUrl, String lang) {
        // lang: "ru" | "kz" | "en"
        if (!baseUrl.contains("/ru/") && !baseUrl.contains("/kz/") && !baseUrl.contains("/en/")) {
            // если почему-то нет языкового сегмента — просто добавим /{lang}/ перед promotions
            // пример: https://1xbet.kz/promotions/autoboom3 -> https://1xbet.kz/{lang}/promotions/autoboom3
            return baseUrl.replace("https://1xbet.kz/", "https://1xbet.kz/" + lang + "/");
        }

        // нормальный случай: меняем существующий языковой сегмент
        return baseUrl
                .replace("/ru/", "/" + lang + "/")
                .replace("/kz/", "/" + lang + "/")
                .replace("/en/", "/" + lang + "/");
    }

    private void waitForPageLoaded(Page page, String url, int index, String lang) {
        try {
            // Ждём, пока утихнет сеть (SPA, ajax и т.п.)
            page.waitForLoadState(
                    LoadState.NETWORKIDLE,
                    new Page.WaitForLoadStateOptions().setTimeout(30_000)
            );

            // Ждём появления ключевых блоков промо/бонуса/хедера/футера
            page.waitForSelector(
                    "header, footer, .bonus-detail, .promo-detail",
                    new Page.WaitForSelectorOptions()
                            .setTimeout(15_000)
                            .setState(WaitForSelectorState.VISIBLE)
            );

            // Небольшая дополнительная пауза, чтобы всё дорисовалось
            page.waitForTimeout(3000);

            System.out.println("✅ Страница #" + index + " [" + lang + "] загружена: " + url);
        } catch (Exception e) {
            System.out.println("⚠ Ошибка загрузки #" + index + " [" + lang + "]: " + url + " — " + e.getMessage());
            // На всякий случай ещё небольшая пауза, чтобы не делать скриншот совсем пустой страницы
            page.waitForTimeout(3000);
        }
    }


    private void takeScreenshot(Page page, String promoName, String lang) {
        try {
            ensureScreenshotsDir();

            String safeName = promoName
                    .replaceAll("[^a-zA-Z0-9а-яА-Я\\s]", "")
                    .replace(" ", "_");
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            String filename = String.format("%s\\%s_%s_%s.png", screenshotsFolder, safeName, lang, timestamp);

            page.screenshot(new Page.ScreenshotOptions()
                    .setPath(Paths.get(filename))
                    .setFullPage(true));

            System.out.println("📸 Скриншот сохранён: " + filename);
        } catch (Exception e) {
            System.out.println("Ошибка скриншота: " + e.getMessage());
        }
    }

    private void ensureScreenshotsDir() {
        try {
            Path dir = Paths.get(screenshotsFolder);
            Files.createDirectories(dir);
        } catch (Exception e) {
            System.out.println("⚠ Не удалось создать папку для скриншотов: " + e.getMessage());
        }
    }

    private String getEscapedScreenshotsFolder() {
        return screenshotsFolder.replace("\\", "\\\\");
    }

    @AfterAll
    static void tearDownAll() {
        System.out.println("Тест завершён ✅ (браузер остаётся открытым)");
    }
}
