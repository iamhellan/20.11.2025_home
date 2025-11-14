package org.example;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitForSelectorState;
import org.junit.jupiter.api.*;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class v3_id_authorization_fastgames {
    static Playwright playwright;
    static Browser browser;
    static BrowserContext context;
    static Page page;
    static TelegramNotifier tg;

    @FunctionalInterface
    private interface GameRunner {
        Page run(Page fromPage);
    }

    // --- Цветные логи ---
    static void log(String text) {
        System.out.println("\u001B[37m" + text + "\u001B[0m");
    }

    static void info(String text) {
        System.out.println("\u001B[36mℹ️  " + text + "\u001B[0m");
    }

    static void success(String text) {
        System.out.println("\u001B[32m✅ " + text + "\u001B[0m");
    }

    static void warn(String text) {
        System.out.println("\u001B[33m⚠️  " + text + "\u001B[0m");
    }

    static void error(String text) {
        System.out.println("\u001B[31m❌ " + text + "\u001B[0m");
    }

    static void section(String name) {
        System.out.println("\n\u001B[45m===== " + name.toUpperCase() + " =====\u001B[0m");
    }

    @BeforeAll
    static void setUpAll() {
        playwright = Playwright.create();

        browser = playwright.chromium().launch(
                new BrowserType.LaunchOptions()
                        .setHeadless(false)
                        .setArgs(List.of(
                                "--start-maximized",
                                "--window-size=1920,1080"
                        ))
        );

        Browser.NewContextOptions options = new Browser.NewContextOptions()
                .setViewportSize(null); // во весь экран

        context = browser.newContext(options);
        page = context.newPage();

        // Глобальные таймауты
        page.setDefaultTimeout(60_000);
        page.setDefaultNavigationTimeout(90_000);

        // Telegram
        String botToken = ConfigHelper.get("telegram.bot.token");
        String chatId = ConfigHelper.get("telegram.chat.id");
        tg = new TelegramNotifier(botToken, chatId);
    }

    @AfterAll
    static void tearDownAll() {
        String keep = null;
        try {
            keep = ConfigHelper.get("keep.browser.open");
        } catch (Exception ignored) {}

        boolean keepBrowser = keep != null && keep.equalsIgnoreCase("true");

        if (keepBrowser) {
            success("Тест завершён ✅ (браузер оставлен открытым по keep.browser.open=true)");
            return;
        }

        success("Тест завершён ✅ (закрываем браузер и Playwright)");

        try { if (context != null) context.close(); } catch (Throwable ignored) {}
        try { if (browser != null) browser.close(); } catch (Throwable ignored) {}
        try { if (playwright != null) playwright.close(); } catch (Throwable ignored) {}
    }

    // ===== ВСПОМОГАТЕЛЬНЫЕ УТИЛИТЫ ============================================================

    private Frame findFrameWithSelector(Page p, String selector, int timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            // 1) Сначала фреймы текущей страницы
            for (Frame f : p.frames()) {
                try {
                    if (f.locator(selector).count() > 0) {
                        System.out.println("[DEBUG] Нашли селектор в фрейме текущей страницы: " + f.url());
                        return f;
                    }
                } catch (Throwable ignore) {}
            }

            // 2) Потом остальные страницы контекста
            for (Page pg : p.context().pages()) {
                if (pg == p) continue;
                for (Frame f : pg.frames()) {
                    try {
                        if (f.locator(selector).count() > 0) {
                            System.out.println("[DEBUG] Нашли селектор в фрейме другой страницы: " + f.url());
                            return f;
                        }
                    } catch (Throwable ignore) {}
                }
            }

            p.waitForTimeout(300);
        }
        return null;
    }

    private Locator smartLocator(Page p, String selector, int timeoutMs) {
        Locator direct = p.locator(selector);
        if (direct.count() > 0) return direct;
        Frame f = findFrameWithSelector(p, selector, timeoutMs);
        if (f != null) return f.locator(selector);
        throw new RuntimeException("Элемент не найден ни на странице, ни во фреймах: " + selector);
    }

    private void robustClick(Page p, Locator loc, int timeoutMs, String debugName) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        RuntimeException lastErr = null;

        while (System.currentTimeMillis() < deadline) {
            try {
                loc.first().scrollIntoViewIfNeeded();
                loc.first().click(new Locator.ClickOptions().setTimeout(3000));
                return;
            } catch (RuntimeException e1) {
                lastErr = e1;
                String msg = e1.getMessage() == null ? "" : e1.getMessage();
                boolean intercept = msg.contains("intercepts pointer events");

                if (intercept) {
                    info("'" + debugName + "': перехват клика. Пробуем через force или JS.");
                    try {
                        loc.first().click(new Locator.ClickOptions().setTimeout(2000).setForce(true));
                        return;
                    } catch (Throwable ignore) {}
                    try {
                        loc.first().evaluate("el => el.dispatchEvent(new MouseEvent('click', {bubbles:true}))");
                        return;
                    } catch (Throwable ignore) {}
                }
            }
            p.waitForTimeout(200);
        }
        if (lastErr != null) throw lastErr;
        throw new RuntimeException("Не удалось кликнуть по '" + debugName + "' за " + timeoutMs + "ms");
    }

    private void clickFirstEnabled(Page p, String selector, int timeoutMs) {
        Locator loc = smartLocator(p, selector, timeoutMs);
        robustClick(p, loc.first(), timeoutMs, selector);
    }

    private void clickFirstEnabledAny(Page p, String[] selectors, int timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            for (String sel : selectors) {
                try {
                    clickFirstEnabled(p, sel, 1500);
                    return;
                } catch (Throwable ignore) {}
            }
            p.waitForTimeout(150);
        }
        throw new RuntimeException("Не нашли активный элемент ни по одному из селекторов!");
    }

    private Page clickCardMaybeOpensNewTab(Locator card) {
        int before = context.pages().size();
        robustClick(page, card, 30_000, "game-card");
        page.waitForTimeout(600);
        int after = context.pages().size();
        if (after > before) {
            Page newPage = context.pages().get(after - 1);
            newPage.bringToFront();
            System.out.println("[DEBUG] Игра открылась в новой вкладке: " + newPage.url());
            return newPage;
        }
        System.out.println("[DEBUG] Игра открылась в текущем окне/фрейме");
        return page;
    }

    private void passTutorialIfPresent(Page gamePage) {
        for (int i = 1; i <= 5; i++) {
            try {
                Locator nextBtn = smartLocator(gamePage, "div[role='button']:has-text('Далее')", 600);
                if (nextBtn.count() == 0 || !nextBtn.first().isVisible()) break;
                robustClick(gamePage, nextBtn.first(), 2000, "Далее");
                gamePage.waitForTimeout(150);
            } catch (Throwable ignore) {
                break;
            }
        }
        try {
            Locator understood = smartLocator(gamePage, "div[role='button']:has-text('Я всё понял')", 600);
            if (understood.count() > 0 && understood.first().isVisible()) {
                robustClick(gamePage, understood.first(), 2000, "Я всё понял");
            }
        } catch (Throwable ignore) {}
    }

    private void setStake50ViaChip(Page gamePage) {
        System.out.println("Выбираем чип 50 KZT");
        Locator chip50 = smartLocator(gamePage, "div.chip-text:has-text('50')", 2000);
        robustClick(gamePage, chip50.first(), 8000, "chip-50");
    }

    // Ожидание нового раунда: ждём, пока хотя бы одна кнопка по селектору станет кликабельной
    private void waitRoundToSettle(Page gamePage, int maxMs, String betButtonSelector) {
        info("Ждём, когда хотя бы одна кнопка ставки по селектору снова станет активной: " + betButtonSelector);

        try {
            gamePage.waitForFunction(
                    "selector => {" +
                            "  const nodes = document.querySelectorAll(selector);" +
                            "  for (const el of nodes) {" +
                            "    if (!el) continue;" +
                            "    const s = window.getComputedStyle(el);" +
                            "    const clickable =" +
                            "      !el.classList.contains('disabled') && " +
                            "      !el.classList.contains('pointer-events-none') && " +
                            "      !el.hasAttribute('disabled') && " +
                            "      s.display !== 'none' && " +
                            "      s.visibility !== 'hidden' && " +
                            "      s.opacity !== '0';" +
                            "    if (clickable) return true;" +
                            "  }" +
                            "  return false;" +
                            "}",
                    betButtonSelector,
                    new Page.WaitForFunctionOptions().setTimeout(maxMs)
            );
            success("Раунд завершился — кнопка ставки снова активна ✅");
        } catch (PlaywrightException e) {
            warn("Раунд не определён как завершён за " + (maxMs / 1000) +
                    " сек (ни одна кнопка по селектору '" + betButtonSelector +
                    "' не стала активной): " + e.getMessage());
        }
    }

    private Page openGameByHrefContains(Page originPage, String hrefContains, String fallbackMenuText) {
        String linkSel = "a[href*='" + hrefContains + "']";
        String fallbackSel = "span.text-hub-header-game-title:has-text('" + fallbackMenuText + "')";

        Frame f = findFrameWithSelector(originPage, linkSel, 5000);
        if (f == null && fallbackMenuText != null) {
            f = findFrameWithSelector(originPage, fallbackSel, 5000);
        }
        if (f == null) throw new RuntimeException("Не нашли ссылку на игру: " + hrefContains);

        Locator link = f.locator(linkSel);
        if (link.count() == 0 && fallbackMenuText != null) {
            link = f.locator(fallbackSel).locator("xpath=ancestor::a");
        }
        return clickCardMaybeOpensNewTab(link.first());
    }

    private Page openUniqueBoxingFromHub(Page originPage) {
        // 1) productId=boxing
        String innerSpan = "a.menu-sports-item-inner[href*='productId=boxing'][href*='cid=1xbetkz'] " +
                "span.text-hub-header-game-title:has-text('Бокс')";

        Frame f = findFrameWithSelector(originPage, innerSpan, 10_000);
        if (f != null) {
            Locator spans = f.locator(innerSpan);
            int count = spans.count();
            if (count == 0) {
                throw new RuntimeException("❌ Нашли фрейм по productId=boxing, но внутри нет span 'Бокс'");
            }
            if (count > 1) {
                info("Нашли " + count + " карточек 'Бокс' с productId=boxing, берём последнюю");
            }
            Locator lastSpan = spans.nth(count - 1);
            Locator link = lastSpan.locator("xpath=ancestor::a");
            if (link.count() == 0) {
                throw new RuntimeException("❌ Нашли 'Бокс' по productId=boxing, но не смогли подняться до <a>-карточки");
            }
            return clickCardMaybeOpensNewTab(link.first());
        }

        // 2) fallback по заголовку
        String boxingSpan = "span.text-hub-header-game-title:has-text('Бокс')";
        f = findFrameWithSelector(originPage, boxingSpan, 10_000);
        if (f == null) {
            throw new RuntimeException("❌ Не нашли текст 'Бокс' в хабе быстрых игр ни по productId, ни по заголовку");
        }

        Locator spans = f.locator(boxingSpan);
        int count = spans.count();
        if (count == 0) {
            throw new RuntimeException("❌ Нашли фрейм, но не нашли ни одного span 'Бокс'");
        }
        if (count > 1) {
            info("Нашли " + count + " карточек 'Бокс' по заголовку, берём последнюю");
        }

        Locator lastSpan = spans.nth(count - 1);
        Locator link = lastSpan.locator("xpath=ancestor::a");
        if (link.count() == 0) {
            throw new RuntimeException("❌ Нашли 'Бокс' по заголовку, но не смогли подняться до <a>-карточки");
        }

        return clickCardMaybeOpensNewTab(link.first());
    }

    // Безопасный запуск игры с возвратом Page, без захвата currentGamePage
    private Page playSafe(String gameName, GameRunner runner, Page fromPage) {
        try {
            return runner.run(fromPage);
        } catch (Exception e) {
            warn("Ошибка при выполнении '" + gameName + "': " + e.getMessage());
            String screenshot = ScreenshotHelper.takeScreenshot(page, "skip_" + gameName);
            if (screenshot != null) {
                tg.sendPhoto(screenshot, "Скриншот для пропущенной игры " + gameName);
            }
            info("Пропускаем игру '" + gameName + "' и продолжаем...");
            return fromPage;
        }
    }

    private void openFastGamesHub() {
        section("Переход в Быстрые игры");
        page.bringToFront();
        page.waitForTimeout(1200);
        page.click("a.header-menu-nav-list-item__link.main-item:has-text('Быстрые игры')");
        page.waitForTimeout(1500);
    }

    // Поиск кода из Google Messages по сохранённой сессии
    private String fetchSmsCodeFromGoogleMessages() {
        log("Ищем файл сессии Google Messages...");

        Path projectRoot = Paths.get(System.getProperty("user.dir"));
        Path[] possiblePaths = new Path[]{
                projectRoot.resolve("resources/sessions/messages-session.json"),
                projectRoot.resolve("src/test/resources/sessions/messages-session.json"),
                projectRoot.resolve("src/test/java/org/example/resources/sessions/messages-session.json")
        };

        Path sessionPath = null;
        for (Path path : possiblePaths) {
            if (path.toFile().exists()) {
                sessionPath = path;
                break;
            }
        }

        if (sessionPath == null) {
            throw new RuntimeException("❌ Файл сессии Google Messages не найден ни в одном из стандартных путей!");
        }

        info("📁 Используем файл сессии: " + sessionPath.toAbsolutePath());

        BrowserContext messagesContext = browser.newContext(
                new Browser.NewContextOptions().setStorageStatePath(sessionPath)
        );
        Page messagesPage = messagesContext.newPage();
        messagesPage.navigate("https://messages.google.com/web/conversations");

        log("Ждём появления списка чатов в Google Messages...");
        boolean chatsLoaded = false;
        for (int i = 0; i < 20; i++) {
            if (messagesPage.locator("mws-conversation-list-item").count() > 0) {
                chatsLoaded = true;
                break;
            }
            messagesPage.waitForTimeout(1000);
        }
        if (!chatsLoaded) {
            messagesContext.close();
            throw new RuntimeException("❌ Чаты не появились в Google Messages — возможно, не успели подгрузиться.");
        }
        success("Список чатов успешно найден");

        log("Ищем чат с 1xBet...");
        Locator chat = messagesPage.locator(
                "mws-conversation-list-item:has-text('1xbet'), " +
                        "mws-conversation-list-item:has-text('1xbet-kz')"
        );
        if (chat.count() == 0) chat = messagesPage.locator("mws-conversation-list-item").first();
        chat.first().click();
        log("Чат открыт");
        messagesPage.waitForTimeout(3000);

        log("Ищем последнее сообщение...");
        Locator messageNodes = messagesPage.locator("div.text-msg-content div.text-msg.msg-content div.ng-star-inserted");
        int count = 0;
        for (int i = 0; i < 15; i++) {
            count = messageNodes.count();
            if (count > 0) break;
            messagesPage.waitForTimeout(1000);
        }
        if (count == 0) {
            messagesContext.close();
            throw new RuntimeException("❌ Не найдено сообщений внутри чата!");
        }

        String lastMessageText = messageNodes.nth(count - 1).innerText().trim();
        log("📨 Последнее сообщение: " + lastMessageText);

        Matcher matcher = Pattern.compile("\\b[a-zA-Z0-9]{4,8}\\b").matcher(lastMessageText);
        String code = matcher.find() ? matcher.group() : null;
        messagesContext.close();

        if (code == null) {
            throw new RuntimeException("❌ Код подтверждения не найден в сообщении!");
        }
        success("✅ Извлечённый код: " + code);
        return code;
    }

    private boolean tryBetButton(Page gamePage, String selector) {
        info("Проверяем кнопку ставки: " + selector);
        long start = System.currentTimeMillis();

        while (System.currentTimeMillis() - start < 30_000) {
            Locator button = gamePage.locator(selector);
            if (button.count() > 0) {
                Locator btn = button.first();
                if (btn.isVisible()) {
                    boolean clickable = false;
                    try {
                        clickable = (Boolean) btn.evaluate(
                                "el => { " +
                                        "const s = window.getComputedStyle(el);" +
                                        "return !el.classList.contains('disabled') && " +
                                        "!el.classList.contains('pointer-events-none') && " +
                                        "!el.hasAttribute('disabled') && " +
                                        "s.display !== 'none' && s.visibility !== 'hidden' && s.opacity !== '0';" +
                                        "}"
                        );
                    } catch (Throwable ignore) {}

                    if (clickable) {
                        success("Кнопка активна — делаем ставку");
                        try {
                            btn.scrollIntoViewIfNeeded();
                            btn.click(new Locator.ClickOptions()
                                    .setTimeout(2_000)
                                    .setForce(true));
                        } catch (Throwable e) {
                            warn("Обычный клик не сработал, пробуем через JS");
                            try {
                                gamePage.evaluate(
                                        "el => el.dispatchEvent(new MouseEvent('click', {bubbles:true}))",
                                        btn.elementHandle()
                                );
                            } catch (Throwable e2) {
                                error("Ошибка при JS-клике: " + e2.getMessage());
                            }
                        }

                        gamePage.waitForTimeout(600);
                        waitRoundToSettle(gamePage, 60_000, selector);
                        return true;
                    }
                }
            }
            gamePage.waitForTimeout(400);
        }

        warn("Кнопка ставки не появилась/не активировалась за 30 сек — пропускаем игру");
        return false;
    }

    // ===== ОТДЕЛЬНЫЕ ИГРЫ ============================================================

    private Page playCrashBox(Page ignored) {
        section("Крэш-Бокс");

        log("Ищем карточку 'Крэш-Бокс' (через href) в фреймах");
        Frame gamesFrame = findFrameWithSelector(page, "a.game[href*='crash-boxing']", 8000);
        if (gamesFrame == null) {
            gamesFrame = findFrameWithSelector(page, "p.game-name:has-text('Крэш-Бокс')", 12_000);
        }
        if (gamesFrame == null) {
            List<Frame> frames = page.frames();
            System.out.println("[DEBUG] Фреймы на странице:");
            for (Frame f : frames) System.out.println(" - " + f.url());
            throw new RuntimeException("❌ Не удалось найти карточку 'Крэш-Бокс' ни в одном iframe");
        }

        Locator crashByHref = gamesFrame.locator("a.game[href*='crash-boxing']");
        Locator crashByText = gamesFrame.locator("p.game-name:has-text('Крэш-Бокс')").locator("xpath=ancestor::a");
        Locator crashCard = crashByHref.count() > 0 ? crashByHref : crashByText;

        log("Ждём появления карточки в DOM");
        crashCard.waitFor(new Locator.WaitForOptions().setTimeout(20_000).setState(WaitForSelectorState.ATTACHED));

        log("Кликаем по 'Крэш-Бокс'");
        Page gamePage = clickCardMaybeOpensNewTab(crashCard);
        gamePage.waitForTimeout(800);

        passTutorialIfPresent(gamePage);

        log("Жмём кнопку 'Мин' для минимальной ставки");
        try {
            Locator minButton = smartLocator(gamePage,
                    "span[role='button']:has-text('Мин')",
                    8000);
            robustClick(gamePage, minButton, 5000, "Мин");
            success("Кнопка 'Мин' нажата ✅");
        } catch (Exception e) {
            warn("Не удалось нажать 'Мин': " + e.getMessage());
        }

        gamePage.waitForTimeout(800);

        String betSelector =
                "div[role='button'][data-market='hit_met_condition'][data-outcome='yes']:has-text('Сделать ставку')";

        log("Ставка 50 KZT (yes)");
        clickFirstEnabled(gamePage, betSelector, 300_000);

        gamePage.waitForTimeout(1500);
        waitRoundToSettle(gamePage, 60_000, betSelector);

        return gamePage;
    }

    private Page playNards(Page fromPage) {
        section("Нарды");
        log("Переходим в игру 'Нарды'");
        Page nardsPage = openGameByHrefContains(fromPage, "nard", "Нарды");
        nardsPage.waitForTimeout(600);

        passTutorialIfPresent(nardsPage);
        setStake50ViaChip(nardsPage);

        String betSelector = "span[role='button'][data-market='dice'][data-outcome='blue']";

        log("Выбираем исход: Синий");
        clickFirstEnabled(nardsPage, betSelector, 300_000);

        waitRoundToSettle(nardsPage, 60_000, betSelector);
        return nardsPage;
    }

    private Page playDarts(Page fromPage) {
        section("Дартс");
        log("Переходим в игру 'Дартс'");
        Page dartsPage = openGameByHrefContains(fromPage, "darts?cid", "Дартс");
        dartsPage.waitForTimeout(600);

        passTutorialIfPresent(dartsPage);
        setStake50ViaChip(dartsPage);

        String betSelector = "span[role='button'][data-market='1-4-5-6-9-11-15-16-17-19']";

        log("Выбираем исход (1-4-5-6-9-11-15-16-17-19)");
        clickFirstEnabled(dartsPage, betSelector, 300_000);

        waitRoundToSettle(dartsPage, 60_000, betSelector);
        return dartsPage;
    }

    private Page playDartsFortune(Page fromPage) {
        section("Дартс - Фортуна");
        log("Переходим в игру 'Дартс - Фортуна'");
        Page dartsFortunePage = openGameByHrefContains(fromPage, "darts-fortune", "Дартс - Фортуна");
        dartsFortunePage.waitForTimeout(600);

        passTutorialIfPresent(dartsFortunePage);

        log("Ожидаем появления чипа '50'");
        try {
            Locator chip50 = smartLocator(dartsFortunePage,
                    "div.chip-text:has-text('50')",
                    60_000);
            chip50.first().waitFor(
                    new Locator.WaitForOptions()
                            .setTimeout(60_000)
                            .setState(WaitForSelectorState.VISIBLE)
            );
            success("Чип '50' появился — можно делать ставку ✅");
        } catch (Exception e) {
            warn("Чип '50' не появился вовремя — продолжаем без него: " + e.getMessage());
        }

        String betSelector = "div[data-outcome='ONE_TO_EIGHT']";

        log("Выбираем исход: ONE_TO_EIGHT (Сектор 1-8)");
        try {
            clickFirstEnabled(dartsFortunePage, betSelector, 45_000);
            success("Исход ONE_TO_EIGHT выбран ✅");
        } catch (Exception e) {
            error("Не удалось выбрать исход ONE_TO_EIGHT: " + e.getMessage());
        }

        waitRoundToSettle(dartsFortunePage, 60_000, betSelector);
        return dartsFortunePage;
    }

    private Page playHilo(Page fromPage) {
        section("Больше / Меньше");
        log("Переходим в игру 'Больше/Меньше'");
        Page hiloPage = openGameByHrefContains(fromPage, "darts-hilo", "Больше/Меньше");
        hiloPage.waitForTimeout(600);

        passTutorialIfPresent(hiloPage);
        setStake50ViaChip(hiloPage);

        String primarySelector =
                "div[role='button'][data-market='THROW_RESULT'][data-outcome='gte-16']";

        log("Выбираем исход: Больше или равно (>=16)");
        clickFirstEnabledAny(hiloPage, new String[]{
                primarySelector,
                "div.board-market-hi-eq:has-text('Больше или равно')"
        }, 300_000);

        waitRoundToSettle(hiloPage, 60_000, primarySelector);
        return hiloPage;
    }

    private Page playShootout(Page fromPage) {
        section("Буллиты NHL21");
        log("Переходим в игру 'Буллиты NHL21'");
        Page shootoutPage = openGameByHrefContains(fromPage, "shootout", "Буллиты NHL21");
        shootoutPage.waitForTimeout(800);

        passTutorialIfPresent(shootoutPage);
        setStake50ViaChip(shootoutPage);

        String betSelector = "div[role='button'].market-button:has-text('Да')";

        log("Выбираем исход: Да");
        clickFirstEnabled(shootoutPage, betSelector, 300_000);

        waitRoundToSettle(shootoutPage, 60_000, betSelector);
        return shootoutPage;
    }

    private Page playBoxing(Page fromPage) {
        section("Бокс");
        log("Переходим в игру 'Бокс' (уникальная кнопка)");
        Page boxingPage = openUniqueBoxingFromHub(fromPage);
        boxingPage.waitForTimeout(600);

        passTutorialIfPresent(boxingPage);
        setStake50ViaChip(boxingPage);

        log("Ожидаем появление панели с исходами");
        boxingPage.waitForSelector("div.contest-panel",
                new Page.WaitForSelectorOptions().setTimeout(120_000).setState(WaitForSelectorState.VISIBLE)
        );

        log("Выбираем исход боксёр №1 (первая кнопка)");
        boolean betDone = tryBetButton(boxingPage,
                "div.contest-panel-outcome-button:has-text('Сделать ставку'), " +
                        "button.contest-panel-outcome-button:has-text('Сделать ставку'), " +
                        "div[role='button'].contest-panel-outcome-button:has-text('Сделать ставку')"
        );

        if (!betDone) {
            warn("Не удалось сделать ставку в 'Бокс' — кнопка не найдена. Возможна новая DOM-структура игры.");
            info("Совет: проверь актуальный селектор вручную через devtools.");
        }

        return boxingPage;
    }

    // ======= ОСНОВНОЙ ТЕСТ ============================================================

    @Test
    void v3_loginAndPlayFastGames() {
        long startTime = System.currentTimeMillis();
        tg.sendMessage("🚀 *Тест v3_id_authorization_fastgames* стартовал (авторизация через ID + быстрые игры)");

        try {
            // --- АВТОРИЗАЦИЯ ---
            log("Открываем сайт 1xbet.kz");
            page.navigate("https://1xbet.kz/");
            page.evaluate("window.moveTo(0,0); window.resizeTo(screen.width, screen.height);");

            log("Жмём 'Войти' в шапке");
            page.waitForTimeout(800);
            page.click("button#login-form-call");

            String login = ConfigHelper.get("login");
            String password = ConfigHelper.get("password");
            log("Вводим ID и пароль из config.properties");
            page.fill("input#auth_id_email", login);
            page.fill("input#auth-form-password", password);

            log("Жмём 'Войти' в форме авторизации");
            Locator loginBtn = page.locator(
                    "button.auth-button.auth-button--block.auth-button--theme-secondary, " +
                            "button.auth-button:has-text('Войти')"
            );
            robustClick(page, loginBtn.first(), 15_000, "Войти");

            log("Ждём появления кнопки 'Выслать код' (до 10 мин)");
            Locator sendCodeBtn = page.locator(
                    "button.phone-sms-modal-content__send, " +
                            "button:has-text('Выслать код')"
            );
            sendCodeBtn.first().waitFor(
                    new Locator.WaitForOptions()
                            .setTimeout(600_000)
                            .setState(WaitForSelectorState.VISIBLE)
            );

            log("Жмём 'Выслать код'");
            robustClick(page, sendCodeBtn.first(), 10_000, "Выслать код");

            log("Ждём поле для кода (до 10 мин)");
            Locator codeInput = page.locator("input.phone-sms-modal-code__input");
            codeInput.first().waitFor(
                    new Locator.WaitForOptions()
                            .setTimeout(600_000)
                            .setState(WaitForSelectorState.VISIBLE)
            );

            log("Достаём код из Google Messages по сохранённой сессии");
            String code = fetchSmsCodeFromGoogleMessages();

            log("Вводим код и подтверждаем вход");
            page.fill("input.phone-sms-modal-code__input", code);

            Locator confirmBtn = page.locator("button:has-text('Подтвердить')");
            robustClick(page, confirmBtn.first(), 10_000, "Подтвердить");
            success("Авторизация завершена ✅");

            // ====== БЫСТРЫЕ ИГРЫ: один заход в хаб, дальше цепочка ======
            openFastGamesHub();
            Page currentGamePage = page;

            currentGamePage = playSafe("crash_box", this::playCrashBox, currentGamePage);
            currentGamePage = playSafe("nards", this::playNards, currentGamePage);
            currentGamePage = playSafe("darts", this::playDarts, currentGamePage);
            currentGamePage = playSafe("darts_fortune", this::playDartsFortune, currentGamePage);
            currentGamePage = playSafe("hilo", this::playHilo, currentGamePage);
            currentGamePage = playSafe("shootout", this::playShootout, currentGamePage);
            currentGamePage = playSafe("boxing", this::playBoxing, currentGamePage);

            // --- Личный кабинет и выход ---
            section("Личный кабинет и выход");

            log("Открываем 'Личный кабинет'");
            page.bringToFront();
            page.waitForTimeout(1000);
            page.click("a.header-lk-box-link[title='Личный кабинет']");

            log("Пробуем закрыть popup-крестик после входа в ЛК (если он вообще есть)");
            try {
                Locator closeCrossLk = page.locator("div.box-modal_close.arcticmodal-close");
                closeCrossLk.waitFor(
                        new Locator.WaitForOptions()
                                .setTimeout(2000)
                                .setState(WaitForSelectorState.ATTACHED)
                );
                if (closeCrossLk.isVisible()) {
                    closeCrossLk.click();
                    success("Крестик в ЛК найден и нажат ✅");
                } else {
                    info("Крестика в ЛК нет — идём дальше");
                }
            } catch (Exception e) {
                info("Всплывашки в ЛК или крестика нет, игнорируем и двигаемся дальше");
            }

            log("Жмём 'Выход'");
            page.waitForTimeout(1000);
            page.click("a.ap-left-nav__item_exit");

            log("Подтверждаем выход кнопкой 'ОК'");
            page.waitForTimeout(1000);
            page.click("button.swal2-confirm.swal2-styled");

            success("Выход завершён ✅");

            long duration = (System.currentTimeMillis() - startTime) / 1000;
            tg.sendMessage(
                    "✅ *v3_id_authorization_fastgames успешно завершён!*\n" +
                            "• Авторизация по ID — выполнена\n" +
                            "• Код из Google Messages получен\n" +
                            "• Быстрые игры отработаны по цепочке (через openGameByHrefContains)\n\n" +
                            "🕒 Время выполнения: *" + duration + " сек.*"
            );

        } catch (Exception e) {
            error("Ошибка: " + e.getMessage());
            String screenshot = ScreenshotHelper.takeScreenshot(page, "v3_id_authorization_fastgames_error");
            tg.sendMessage("🚨 Ошибка в тесте *v3_id_authorization_fastgames*:\n" + e.getMessage());
            if (screenshot != null) tg.sendPhoto(screenshot, "Скриншот ошибки");
            throw e;
        }
    }
}
