package org.tinystruct.app;

import org.tinystruct.AbstractApplication;
import org.tinystruct.ApplicationContext;
import org.tinystruct.ApplicationException;
import org.tinystruct.app.tools.DatabaseTool;
import org.tinystruct.mcp.MCPClient;
import org.tinystruct.mcp.MCPSpecification;
import org.tinystruct.data.component.Builder;
import org.tinystruct.data.component.Builders;
import org.tinystruct.http.SSEPushManager;
import org.tinystruct.net.URLRequest;
import org.tinystruct.net.URLResponse;
import org.tinystruct.net.handlers.HTTPHandler;
import org.tinystruct.system.ApplicationManager;
import org.tinystruct.system.Dispatcher;
import org.tinystruct.system.annotation.Action;
import org.tinystruct.system.annotation.Argument;
import org.tinystruct.typesafe.client.HttpTypesafeClient;
import org.tinystruct.typesafe.client.RoutingRequest;
import org.tinystruct.typesafe.client.RoutingResult;
import org.tinystruct.typesafe.core.config.RoutingSettings;
import org.tinystruct.typesafe.core.config.TypesafeConfig;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Agent application for autonomous coding tasks.
 */
public class SmartDBA extends AbstractApplication {
    private static final Logger logger = Logger.getLogger(SmartDBA.class.getName());
    private static final String DEFAULT_OPENAI_API_URL = "https://api.openai.com/v1/chat/completions";
    private static final String DEFAULT_MODEL = "gemini-3.5-flash-lite";
    private static final String GEMINI_MODELS_URL = "https://generativelanguage.googleapis.com/v1beta/models/";
    private String apiKey;
    private String apiUrl;
    private String model;
    private String skill;
    /** Root that the model-driven read/write actions are confined to. */
    private java.nio.file.Path workspace = java.nio.file.Paths.get(".").toAbsolutePath().normalize();
    private RoutingSettings routingSettings;
    private MCPClient mcpClient;
    private static final String HISTORY_FILE = ".agent_history.json";
    /** Cap on file content fed back to the model, so one large file can't flood the context window. */
    private static final int MAX_READ_CHARS = 20000;

    private static final String SYSTEM_PROMPT = "You are SmartDBA, an autonomous database agent. You need display data as table style. You have access to the following MCP database tools:\n" +
            "- db/list-tables\n" +
            "- db/describe: requires param 'table'\n" +
            "- db/query: requires param 'table', and optional 'where' and 'limit' params\n" +
            "- db/insert: requires params 'table', 'data'\n" +
            "- db/update: requires params 'table', 'data', 'where'\n" +
            "- db/delete: requires params 'table', 'where'\n" +
            "- db/execute: requires param 'sql'. Use this for complex queries involving JOINs, subqueries, or CTEs (WITH).\n" +
            "\n" +
            "SQL DIALECT RULES (important):\n" +
            "- NEVER include a trailing semicolon in SQL passed to db/execute.\n" +
            "- When using H2 database, the following words are reserved and MUST be double-quoted\n" +
            "  as identifiers whenever used as table or column names:\n" +
            "  USER, GROUP, ORDER, VALUE, KEY, INDEX, SCHEMA, CATALOG, ROLE, CONSTRAINT,\n" +
            "  CROSS, CURRENT, DISTINCT, EXCEPT, EXISTS, FETCH, FOR, FOREIGN, FROM, FULL,\n" +
            "  HAVING, INNER, INTERSECT, IS, JOIN, LIKE, LIMIT, MINUS, NATURAL, NOT,\n" +
            "  NULL, OFFSET, ON, ORDER, PRIMARY, RIGHT, ROWNUM, SELECT, SYSDATE, SYSTIME,\n" +
            "  SYSTIMESTAMP, TODAY, TOP, TRUE, UNION, WHERE, WITH.\n" +
            "  Example: SELECT COUNT(*) FROM \"user\" -- not FROM user\n" +
            "\n" +
            "To use these tools, you MUST output a JSON block at the end of your response in this format:\n" +
            "```json\n" +
            "[\n" +
            "  {\"action\": \"mcp\", \"tool\": \"toolName\", \"params\": {\"key\": \"value\"}}\n" +
            "]\n" +
            "```\n" +
            "You can also use 'read' and 'write' actions with 'path' and 'content' for file operations. Be concise and precise.";

    // ANSI Color codes
    private static final String RESET = "\u001b[0m";
    private static final String BOLD = "\u001b[1m";
    private static final String RED = "\u001b[31m";
    private static final String GREEN = "\u001b[32m";
    private static final String YELLOW = "\u001b[33m";
    private static final String BLUE = "\u001b[34m";
    private static final String MAGENTA = "\u001b[35m";
    private static final String CYAN = "\u001b[36m";
    private static final String WHITE = "\u001b[37m";

    private static final String KEYWORD = MAGENTA;
    private static final String STRING = GREEN;
    private static final String COMMENT = CYAN;
    private static final String NUMBER = YELLOW;
    private static final String CLASS = BLUE;

    @Override
    public void init() {
        this.setTemplateRequired(false);
        this.apiKey = this.getConfiguration().get("agent.api_key");
        this.apiUrl = this.getConfiguration().get("agent.api_url");
        this.model = this.getConfiguration().get("agent.model");
        if (this.model == null || this.model.isEmpty()) {
            this.model = DEFAULT_MODEL;
        }
        if (this.apiUrl == null || this.apiUrl.isEmpty()) {
            String geminiModel = this.model.toLowerCase().contains("gemini") ? this.model : DEFAULT_MODEL;
            this.apiUrl = GEMINI_MODELS_URL + geminiModel + ":generateContent";
        }

        String workspaceDir = this.getConfiguration().get("agent.workspace");
        this.workspace = java.nio.file.Paths.get(workspaceDir == null || workspaceDir.isBlank() ? "." : workspaceDir)
                .toAbsolutePath().normalize();

        // Read TypeSafe routing settings (confirm-actions list, confidence thresholds, model)
        this.routingSettings = RoutingSettings.from(this.getConfiguration());

        ApplicationManager.install(new Dispatcher());
        ApplicationManager.install(new SmartDBAServer());
        Thread thread = new Thread(() -> {
            try {
                ApplicationManager.call("start", new ApplicationContext(), Action.Mode.CLI);
            } catch (ApplicationException e) {
                logger.log(Level.WARNING, "Error starting SmartDBA", e);
                throw new RuntimeException(e);
            }
        });
        thread.start();

        String skillFile = this.getConfiguration().get("agent.skill_file");
        if (skillFile != null && !skillFile.isEmpty()) {
            try {
                java.nio.file.Path path = java.nio.file.Paths.get(skillFile);
                if (java.nio.file.Files.exists(path)) {
                    this.skill = java.nio.file.Files.readString(path);
                }
            } catch (Exception e) {
                logger.warning("Could not load skill file: " + e.getMessage());
            }
        }

        // Connect to the MCP server that SmartDBAServer exposes.
        // The client connects lazily on first use because the HTTP server
        // starts on a background thread after init() returns.
        String mcpUrl = this.getConfiguration().get("mcp.server.url");
        if (mcpUrl == null || mcpUrl.isEmpty()) {
            mcpUrl = "http://localhost:8080/";
        }
        String mcpToken = this.getConfiguration().get("mcp.auth.token");
        this.mcpClient = new MCPClient(mcpUrl, mcpToken);
    }

    @Override
    public String version() {
        return "1.0.0";
    }

    @Action(value = "chat", description = "Chat with the agent", options = {
            @Argument(key = "message", description = "Message to the agent")
    })
    public String chat() throws ApplicationException {
        String message = "";
        if (getContext().getAttribute("--message") != null) {
            message = getContext().getAttribute("--message").toString();
        }

        if (message.isEmpty()) {
            return "Error: No message provided. Use --message \"your message\".";
        }

        // HTTP/API callers get raw markdown back; live token deltas are still
        // pushed over SSE (see push() / SSEPushManager) for streaming UIs.
        return internalChat(message, false);
    }

    private void printBanner() {
        System.out.println(CYAN + BOLD + "╔═══════════════════════════════════════════════════════════════╗" + RESET);
        System.out.println(CYAN + BOLD + "║                                                               ║" + RESET);
        System.out.println(CYAN + BOLD + "║   " + WHITE + "SmartDBA - Autonomous Database Agent" + CYAN + "                        ║" + RESET);
        System.out.println(CYAN + BOLD + "║   " + YELLOW + "Version " + version() + CYAN + "                                               ║" + RESET);
        System.out.println(CYAN + BOLD + "║                                                               ║" + RESET);
        System.out.println(CYAN + BOLD + "╚═══════════════════════════════════════════════════════════════╝" + RESET);
        System.out.println(WHITE + "Type 'exit' or 'quit' to leave. Type 'clear' to reset history.\n" + RESET);
    }

    @Action(value = "chat", description = "Start an interactive chat session", mode = Action.Mode.CLI)
    public void interactive() throws ApplicationException {
        try {
            // Wait a moment for the server startup output to finish
            Thread.sleep(1500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        printBanner();
        java.util.Scanner scanner = new java.util.Scanner(System.in);
        while (true) {
            System.out.print(BLUE + BOLD + "You > " + RESET);
            if (!scanner.hasNextLine()) break;
            String input = scanner.nextLine().trim();
            if (input.equalsIgnoreCase("exit") || input.equalsIgnoreCase("quit")) {
                System.out.println(YELLOW + "Goodbye!" + RESET);
                System.exit(0);
                break;
            }
            if (input.equalsIgnoreCase("clear")) {
                System.out.println(clear());
                continue;
            }
            if (input.isEmpty()) continue;

            System.out.print("\n" + GREEN + BOLD + "Agent > " + RESET);
            try {
                internalChat(input, true); // streams formatted markdown straight to stdout
            } catch (ApplicationException e) {
                // A failed request ends the turn, not the session. It is printed rather than
                // logged because logging.enabled is FALSE by default, so a logged failure is
                // one the user never hears about.
                System.out.println(RED + "Request failed: " + e.getMessage() + RESET);
            }
            System.out.println("\n");
        }
    }

    /**
     * Renders Markdown as ANSI-formatted text incrementally, chunk by chunk,
     * as it arrives from a streaming API response. Lines belonging to a
     * trailing ```json action block are buffered but never printed, since
     * that block is machine-readable tool-call instructions, not chat output.
     */
    final class StreamRenderer { // package-private so its buffering can be tested directly
        private final StringBuilder pending = new StringBuilder();
        private final java.util.List<String> tableBuffer = new java.util.ArrayList<>();
        private boolean inFence = false;
        private boolean fenceIsJson = false;
        private String fenceLang = "";
        private int codeLineNum = 0;
        private boolean rendered = false;
        private boolean suppressedToolCall = false;

        void feed(String delta) {
            pending.append(delta);
            int newlineIdx;
            while ((newlineIdx = pending.indexOf("\n")) != -1) {
                String line = pending.substring(0, newlineIdx);
                pending.delete(0, newlineIdx + 1);
                processLine(line);
            }
        }

        /**
         * Flushes what the stream left behind, and is safe to call on a stream that died
         * half-way — which is the point of calling it from a {@code finally}. A response's last
         * line usually arrives with no trailing newline, so it is still sitting in {@code pending}
         * when the stream ends; without this it would never be printed at all.
         */
        void finish() {
            if (pending.length() > 0) {
                processLine(pending.toString());
                pending.setLength(0);
            }
            flushTable();
            if (inFence && !fenceIsJson) {
                System.out.println(CYAN + "└──" + RESET); // the stream ended inside a code block
            }
            inFence = false;
            fenceIsJson = false;
            System.out.flush();
        }

        /** {@code true} if nothing reached the screen, so the caller can say why instead of leaving a blank. */
        boolean renderedNothing() {
            return !rendered;
        }

        /** {@code true} if a tool-call block was swallowed — which is why the screen may be blank. */
        boolean suppressedToolCall() {
            return suppressedToolCall;
        }

        /**
         * A Markdown table row, which has to start with a pipe. Accepting any line that merely
         * contained a couple of pipes swallowed ordinary prose — "a | b", SQL using {@code ||}, a
         * regex alternation — and re-rendered the sentence as a mangled table.
         */
        private boolean looksLikeTableRow(String line) {
            String t = line.trim();
            return t.startsWith("|") && t.indexOf('|', 1) != -1;
        }

        private void processLine(String rawLine) {
            String line = rawLine.endsWith("\r") ? rawLine.substring(0, rawLine.length() - 1) : rawLine;
            String trimmed = line.trim();

            // Code fences and table blocks can't overlap; entering either one
            // closes out the other first.
            if (trimmed.startsWith("```")) {
                flushTable();
                if (!inFence) {
                    inFence = true;
                    fenceLang = trimmed.substring(3).trim().toLowerCase();
                    fenceIsJson = fenceLang.equals("json");
                    codeLineNum = 0;
                    if (!fenceIsJson) {
                        System.out.println(CYAN + "┌─ " + (fenceLang.isEmpty() ? "code" : fenceLang) + " " + RESET);
                        rendered = true;
                    }
                } else {
                    inFence = false;
                    if (!fenceIsJson) {
                        System.out.println(CYAN + "└──" + RESET);
                        rendered = true;
                    }
                    fenceIsJson = false;
                }
                return;
            }

            if (inFence) {
                if (fenceIsJson) {
                    suppressedToolCall = true;
                    return; // tool-call payload, not shown to the user
                }
                codeLineNum++;
                if (fenceLang.equals("java")) {
                    System.out.println(highlightJavaLine(line, codeLineNum));
                } else {
                    System.out.println(WHITE + line + RESET);
                }
                rendered = true;
                return;
            }

            // Pipe-table rows are buffered until the table block ends, since
            // column widths can only be known once every row has been seen -
            // rendering each row independently misaligns the borders.
            if (looksLikeTableRow(line)) {
                tableBuffer.add(line);
                return;
            }
            flushTable();
            System.out.println(renderMarkdownLine(line));
            // Blank lines don't count: a reply made only of newlines still told the user nothing.
            if (!line.isBlank()) rendered = true;
        }

        /** Renders all buffered table rows as one width-aligned box-drawn table. */
        private void flushTable() {
            if (tableBuffer.isEmpty()) return;
            rendered = true;

            java.util.List<String[]> rows = new java.util.ArrayList<>();
            java.util.List<Boolean> separatorRow = new java.util.ArrayList<>();
            int maxCols = 0;
            for (String raw : tableBuffer) {
                String t = raw.trim().replaceAll("^\\|", "").replaceAll("\\|$", "");
                String[] cells = t.split("\\|");
                for (int i = 0; i < cells.length; i++) cells[i] = cells[i].trim();
                boolean isSep = true;
                for (String c : cells) {
                    if (!c.matches(":?-+:?")) { isSep = false; break; }
                }
                rows.add(cells);
                separatorRow.add(isSep);
                maxCols = Math.max(maxCols, cells.length);
            }
            tableBuffer.clear();
            if (maxCols == 0) return;

            int[] widths = new int[maxCols];
            for (int r = 0; r < rows.size(); r++) {
                if (separatorRow.get(r)) continue;
                String[] cells = rows.get(r);
                for (int c = 0; c < cells.length; c++) {
                    widths[c] = Math.max(widths[c], stripMarkdownMarkers(cells[c]).length());
                }
            }
            for (int c = 0; c < maxCols; c++) widths[c] = Math.max(widths[c], 3);

            System.out.println(CYAN + tableBorder(widths, "┌", "┬", "┐") + RESET);
            boolean headerDrawn = false;
            for (int r = 0; r < rows.size(); r++) {
                if (separatorRow.get(r)) {
                    System.out.println(CYAN + tableBorder(widths, "├", "┼", "┤") + RESET);
                    headerDrawn = true;
                    continue;
                }
                printTableRow(rows.get(r), widths, r == 0 && !headerDrawn);
            }
            System.out.println(CYAN + tableBorder(widths, "└", "┴", "┘") + RESET);
        }

        private String tableBorder(int[] widths, String left, String mid, String right) {
            StringBuilder sb = new StringBuilder(left);
            for (int c = 0; c < widths.length; c++) {
                sb.append("─".repeat(widths[c] + 2));
                sb.append(c < widths.length - 1 ? mid : right);
            }
            return sb.toString();
        }

        private void printTableRow(String[] cells, int[] widths, boolean isHeader) {
            StringBuilder line = new StringBuilder(CYAN + "│" + RESET);
            for (int c = 0; c < widths.length; c++) {
                String content = c < cells.length ? cells[c] : "";
                int plainLen = stripMarkdownMarkers(content).length();
                String rendered = isHeader ? BOLD + content + RESET : applyInlineEmphasis(content);
                int pad = Math.max(0, widths[c] - plainLen);
                line.append(" ").append(rendered).append(" ".repeat(pad)).append(" ").append(CYAN + "│" + RESET);
            }
            System.out.println(line);
        }
    }

    private String stripMarkdownMarkers(String s) {
        return s.replaceAll("[*_`]", "");
    }

    private String highlightJavaLine(String code, int lineNum) {
        String[] keywords = {"abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
                "const", "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally",
                "float", "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long",
                "native", "new", "package", "private", "protected", "public", "return", "short", "static",
                "strictfp", "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try",
                "void", "volatile", "while", "true", "false", "null"};

        String result = String.format(WHITE + "%3d: " + RESET, lineNum) + code;
        result = result.replaceAll("(\".*?\")", STRING + "$1" + RESET);
        for (String kw : keywords) {
            result = result.replaceAll("\\b" + kw + "\\b", KEYWORD + kw + RESET);
        }
        result = result.replaceAll("(//.*)", COMMENT + "$1" + RESET);
        result = result.replaceAll("\\b(\\d+)\\b", NUMBER + "$1" + RESET);
        return result;
    }

    /**
     * Renders a single line of Markdown (outside of code fences) as ANSI text:
     * headers, bold/italic emphasis, inline code, bullet/numbered lists,
     * blockquotes, horizontal rules and pipe tables.
     */
    private String renderMarkdownLine(String line) {
        if (line.trim().isEmpty()) return "";

        java.util.regex.Matcher hr = java.util.regex.Pattern.compile("^(-{3,}|\\*{3,}|_{3,})$").matcher(line.trim());
        if (hr.matches()) {
            return CYAN + "─".repeat(60) + RESET;
        }

        java.util.regex.Matcher header = java.util.regex.Pattern.compile("^(#{1,6})\\s+(.*)$").matcher(line);
        if (header.matches()) {
            int level = header.group(1).length();
            String text = applyInlineEmphasis(header.group(2));
            String color = level == 1 ? CYAN : level == 2 ? BLUE : MAGENTA;
            return BOLD + color + text + RESET;
        }

        java.util.regex.Matcher quote = java.util.regex.Pattern.compile("^>\\s?(.*)$").matcher(line);
        if (quote.matches()) {
            return CYAN + "│ " + RESET + applyInlineEmphasis(quote.group(1));
        }

        java.util.regex.Matcher bullet = java.util.regex.Pattern.compile("^(\\s*)[-*+]\\s+(.*)$").matcher(line);
        if (bullet.matches()) {
            return bullet.group(1) + YELLOW + "• " + RESET + applyInlineEmphasis(bullet.group(2));
        }

        java.util.regex.Matcher numbered = java.util.regex.Pattern.compile("^(\\s*)(\\d+)\\.\\s+(.*)$").matcher(line);
        if (numbered.matches()) {
            return numbered.group(1) + YELLOW + numbered.group(2) + ". " + RESET + applyInlineEmphasis(numbered.group(3));
        }

        return applyInlineEmphasis(line);
    }

    private String applyInlineEmphasis(String text) {
        // Protect inline code spans from bold/italic regex first.
        java.util.List<String> codeSpans = new java.util.ArrayList<>();
        java.util.regex.Matcher codeMatcher = java.util.regex.Pattern.compile("`([^`]+)`").matcher(text);
        StringBuilder withPlaceholders = new StringBuilder();
        int last = 0;
        while (codeMatcher.find()) {
            withPlaceholders.append(text, last, codeMatcher.start());
            withPlaceholders.append("\u0000").append(codeSpans.size()).append("\u0000");
            codeSpans.add(codeMatcher.group(1));
            last = codeMatcher.end();
        }
        withPlaceholders.append(text.substring(last));
        String result = withPlaceholders.toString();

        result = result.replaceAll("\\*\\*([^*]+)\\*\\*", BOLD + "$1" + RESET);
        result = result.replaceAll("__([^_]+)__", BOLD + "$1" + RESET);
        result = result.replaceAll("(?<![*\\w])\\*([^*]+)\\*(?!\\w)", "\u001b[3m$1" + RESET);
        result = result.replaceAll("(?<![_\\w])_([^_]+)_(?!\\w)", "\u001b[3m$1" + RESET);

        for (int i = 0; i < codeSpans.size(); i++) {
            result = result.replace("\u0000" + i + "\u0000", YELLOW + codeSpans.get(i) + RESET);
        }
        return result;
    }

    /**
     * The outcome of one streaming call: either what the model said, or why it said nothing.
     *
     * <p>The two are separate kinds of thing, and conflating them was a bug. A failure returned as
     * ordinary text was filed in the conversation as an assistant message, saved, and replayed as
     * context on the next request — so the model was told it had once answered
     * "API returned status 429".
     */
    record Completion(String text, String failure) { // package-private so it can be tested directly

        static Completion spoken(String text) {
            return new Completion(text == null ? "" : text, null);
        }

        static Completion failed(String reason) {
            return new Completion("", reason);
        }

        boolean failed() {
            return failure != null;
        }
    }

    /**
     * @param message message from the user
     * @param cli      true when called from the interactive terminal session, in which
     *                 case the response is rendered as ANSI-formatted Markdown and
     *                 printed to stdout live, chunk by chunk, as it streams in.
     *                 Regardless of this flag, raw Markdown deltas are also pushed
     *                 over SSE for any connected web client to render.
     */
    private String internalChat(String message, boolean cli) throws ApplicationException {
        if (this.apiKey == null || this.apiKey.isEmpty()) {
            String err = "Error: API key not configured. Please set 'agent.api_key' in application.properties.";
            if (cli) System.out.print(RED + err + RESET);
            return err;
        }

        // 1. Load history
        Builders history = loadHistory();

        // Add user message
        if (message != null && !message.isEmpty()) {
            Builder userMsg = new Builder();
            userMsg.put("role", "user");
            userMsg.put("content", message);
            history.add(userMsg);
        }

        StringBuilder finalResponse = new StringBuilder();
        boolean hasActions = true;
        String sessionId = getContext() != null ? getContext().getId() : null;

        while (hasActions) {
            boolean isGemini = this.apiUrl.contains("generativelanguage.googleapis.com");
            Builder payload = isGemini ? prepareGeminiPayload(history) : prepareOpenAIPayload(history);

            StreamRenderer renderer = cli ? new StreamRenderer() : null;
            Completion completion;
            try {
                completion = callApiStreaming(payload, isGemini, delta -> {
                    if (renderer != null) {
                        renderer.feed(delta);
                    }
                    if (sessionId != null) {
                        Builder chunkMsg = new Builder();
                        chunkMsg.put("status", "stream");
                        chunkMsg.put("delta", delta);
                        SSEPushManager.getInstance().push(sessionId, chunkMsg);
                    }
                });
            } finally {
                // A stream that failed part-way still has buffered output to flush.
                if (renderer != null) renderer.finish();
            }

            // A failure is not something the assistant said: show it, and leave the conversation
            // as it was so the next request is not primed with our own error message.
            if (completion.failed()) {
                if (cli) System.out.println(RED + completion.failure() + RESET);
                appendTo(finalResponse, completion.failure());
                break;
            }

            String responseText = completion.text();
            if (renderer != null && renderer.renderedNothing()) {
                announceBlankTurn(renderer);
            }

            // Add assistant response to history
            Builder assistantMsg = new Builder();
            assistantMsg.put("role", "assistant");
            assistantMsg.put("content", responseText);
            history.add(assistantMsg);

            // Process any actions requested by the AI
            hasActions = processActions(responseText, history, cli);
            saveHistory(history);

            appendTo(finalResponse, responseText.replaceAll("```json[\\s\\S]*?```", "").trim());

            if (hasActions && cli) {
                System.out.print("\n" + CYAN + "Waiting for results..." + RESET + "\n");
            }
        }

        if (sessionId != null) {
            Builder doneMsg = new Builder();
            doneMsg.put("status", "done");
            SSEPushManager.getInstance().push(sessionId, doneMsg);
        }

        return finalResponse.toString();
    }

    /** Adds a paragraph to the reply being assembled across tool-calling rounds. */
    private static void appendTo(StringBuilder reply, String paragraph) {
        if (paragraph == null || paragraph.isEmpty()) return;
        if (reply.length() > 0) reply.append("\n\n");
        reply.append(paragraph);
    }

    /**
     * Explains a turn that printed nothing, so the prompt never comes back bare.
     *
     * <p>Which kind of nothing it was comes from the renderer, which knows whether it suppressed
     * a tool-call block — not from re-reading the response text and guessing.
     */
    private static void announceBlankTurn(StreamRenderer renderer) {
        System.out.println(renderer.suppressedToolCall()
                ? MAGENTA + "(tool call only — no message)" + RESET
                : YELLOW + "(the model returned no text)" + RESET);
    }

    /**
     * Performs a streaming POST request against the configured LLM API and invokes
     * {@code onDelta} with each incremental text fragment as it arrives over the
     * wire (Server-Sent Events for both the OpenAI-compatible and Gemini APIs).
     *
     * @return what the model said, or a {@link Completion#failed} carrying why it said nothing
     */
    private Completion callApiStreaming(Builder payload, boolean isGemini, Consumer<String> onDelta) throws ApplicationException {
        try {
            URL url;
            if (isGemini) {
                String streamUrl = this.apiUrl.replace(":generateContent", ":streamGenerateContent");
                url = URI.create(streamUrl + "?alt=sse&key=" + this.apiKey).toURL();
            } else {
                url = URI.create(this.apiUrl).toURL();
            }

            URLRequest request = new URLRequest(url);
            request.setMethod("POST").setHeader("Content-Type", "application/json");
            if (!isGemini) {
                request.setHeader("Authorization", "Bearer " + this.apiKey);
            }
            request.setBody(payload.toString());

            StringBuilder full = new StringBuilder();
            StringBuilder rawLines = new StringBuilder();
            AtomicInteger unreadableChunks = new AtomicInteger();

            HTTPHandler handler = new HTTPHandler();
            // The consumer overload always streams: HTTPResponse reads the body
            // line-by-line and invokes the consumer as each line arrives, rather
            // than buffering the whole response before returning.
            URLResponse response = handler.handleRequest(request, line -> {
                rawLines.append(line);
                String trimmed = line.trim();
                if (!trimmed.startsWith("data:")) return;
                String data = trimmed.substring(5).trim();
                if (data.isEmpty() || data.equals("[DONE]")) return;

                try {
                    Builder chunk = new Builder();
                    chunk.parse(data);
                    String delta = isGemini ? extractGeminiDelta(chunk) : extractOpenAIDelta(chunk);
                    if (delta != null && !delta.isEmpty()) {
                        full.append(delta);
                        onDelta.accept(delta);
                    }
                } catch (Exception parseErr) {
                    unreadableChunks.incrementAndGet();
                    logger.fine("Skipping unparsable stream chunk: " + parseErr.getMessage());
                }
            });

            if (response.getStatusCode() != 200) {
                String errorMessage = "Unknown";
                try {
                    Builder errorBody = new Builder();
                    errorBody.parse(rawLines.toString());
                    Builder error = (Builder) errorBody.get("error");
                    if (error != null && error.get("message") != null) {
                        errorMessage = error.get("message").toString();
                    }
                } catch (Exception ignored) {
                    // fall back to raw body if parsing fails
                }
                return Completion.failed("API returned status " + response.getStatusCode() + ": " + errorMessage);
            }

            // A 200 whose every chunk was unreadable is indistinguishable from an empty answer,
            // and the skips are only logged — which says nothing while logging.enabled is FALSE.
            if (full.length() == 0 && unreadableChunks.get() > 0) {
                return Completion.failed("The response could not be read: " + unreadableChunks
                        + " stream chunk(s) did not match the expected " + (isGemini ? "Gemini" : "OpenAI")
                        + " format. Check that agent.api_url points at the right API.");
            }

            return Completion.spoken(full.toString());
        } catch (ApplicationException ae) {
            throw ae;
        } catch (MalformedURLException e) {
            throw new ApplicationException("Invalid API URL: " + this.apiUrl, e);
        } catch (Exception e) {
            throw new ApplicationException("Streaming API call failed: " + e.getMessage(), e);
        }
    }

    private String extractOpenAIDelta(Builder chunk) {
        Builders choices = (Builders) chunk.get("choices");
        if (choices != null && choices.size() > 0) {
            Builder first = choices.get(0);
            Builder delta = (Builder) first.get("delta");
            if (delta != null && delta.get("content") != null) {
                return delta.get("content").toString();
            }
        }
        return "";
    }

    private String extractGeminiDelta(Builder chunk) {
        Builders candidates = (Builders) chunk.get("candidates");
        if (candidates != null && candidates.size() > 0) {
            Builder first = candidates.get(0);
            Builder content = (Builder) first.get("content");
            if (content != null) {
                Builders parts = (Builders) content.get("parts");
                if (parts != null && parts.size() > 0 && parts.get(0).get("text") != null) {
                    return parts.get(0).get("text").toString();
                }
            }
        }
        return "";
    }

    @Action(value = "clear", description = "Clear chat history")
    public String clear() {
        try {
            java.nio.file.Files.deleteIfExists(java.nio.file.Paths.get(HISTORY_FILE));
            return GREEN + "History cleared." + RESET;
        } catch (java.io.IOException e) {
            return RED + "Error clearing history: " + e.getMessage() + RESET;
        }
    }

    private Builders loadHistory() {
        Builders history = new Builders();
        try {
            java.nio.file.Path path = java.nio.file.Paths.get(HISTORY_FILE);
            if (java.nio.file.Files.exists(path)) {
                history.parse(java.nio.file.Files.readString(path));
            }
        } catch (Exception e) {
            logger.warning("Could not load history: " + e.getMessage());
        }
        return history;
    }

    private void saveHistory(Builders history) {
        try {
            java.nio.file.Files.writeString(java.nio.file.Paths.get(HISTORY_FILE), history.toString());
        } catch (Exception e) {
            logger.warning("Could not save history: " + e.getMessage());
        }
    }

    private Builder prepareOpenAIPayload(Builders history) {
        Builder payload = new Builder();
        payload.put("model", this.model != null && !this.model.isEmpty() ? this.model : "gpt-4-turbo-preview");
        payload.put("stream", true);

        Builders messages = new Builders();
        Builder systemMessage = new Builder();
        systemMessage.put("role", "system");
        String prompt = SYSTEM_PROMPT;

        if (this.skill != null) {
            prompt += "\n\nReference Document (Expert Guidance):\n" + this.skill;
        }

        systemMessage.put("content", prompt);
        messages.add(systemMessage);

        for (int i = 0; i < history.size(); i++) {
            messages.add(history.get(i));
        }

        payload.put("messages", messages);
        return payload;
    }

    private Builder prepareGeminiPayload(Builders history) {
        // Gemini format: {"contents": [{"role": "user", "parts": [{"text": "..."}]}, {"role": "model", "parts": [{"text": "..."}]}]}
        Builder payload = new Builder();
        Builders contents = new Builders();

        // System instruction as a preamble
        String systemInstruction = "System Instruction: " + SYSTEM_PROMPT + "\n\n";

        if (this.skill != null) {
            systemInstruction += "Reference Document (Expert Guidance):\n" + this.skill + "\n\n";
        }

        for (int i = 0; i < history.size(); i++) {
            Builder msg = history.get(i);
            String role = msg.get("role").toString();
            String content = msg.get("content").toString();

            Builder geminiMsg = new Builder();
            geminiMsg.put("role", role.equals("assistant") ? "model" : "user");

            Builders parts = new Builders();
            Builder part = new Builder();

            String text = content;
            if (i == 0 && role.equals("user")) {
                text = systemInstruction + text;
            }

            part.put("text", text);
            parts.add(part);
            geminiMsg.put("parts", parts);
            contents.add(geminiMsg);
        }

        payload.put("contents", contents);
        return payload;
    }

    /**
     * Asks the TypeSafe JEV model a single yes/no question: "Does this SQL statement modify or
     * delete data?"  Used only for {@code db/execute}, where the tool name alone is insufficient
     * to decide — the actual SQL content determines the risk.
     *
     * <p>All other tools use the static {@code typesafe.routing.confirm-actions} list defined
     * in {@code application.properties}, which is read once into {@link #routingSettings}.
     *
     * @param sql the SQL statement the AI wants to execute
     * @return {@code true} (requires approval) when JEV says yes or when the call fails
     */
    private boolean jevNeedsApproval(String sql) {
        // Deterministic floor: anything not provably read-only needs approval, whatever JEV says
        // (or whether a TypeSafe key is configured at all).
        if (!DatabaseTool.isReadOnlySql(sql)) {
            return true;
        }
        String typesafeApiKey = this.getConfiguration().get(TypesafeConfig.API_KEY);
        if (typesafeApiKey == null || typesafeApiKey.isBlank()) {
            typesafeApiKey = System.getenv("TYPESAFE_API_KEY");
        }
        if (typesafeApiKey == null || typesafeApiKey.isBlank()) {
            return false; // already classified read-only above
        }

        try {
            // Build a single noul ("yes/no") question using the TypeSafe /v1/systemone API
            HttpTypesafeClient jevClient = new HttpTypesafeClient(
                    this.getConfiguration().get(TypesafeConfig.ENDPOINT),
                    typesafeApiKey,
                    routingSettings.model(),
                    5000, 30000, 2, 500);

            Builder questions = new Builder();
            Builder q = new Builder();
            q.put("type", "noul");
            q.put("question", "Does this SQL statement insert, update, delete, drop, truncate, alter, " +
                    "create, grant, or revoke data or schema objects?");
            questions.put("destructive_sql", q);

            RoutingResult result = jevClient.classify(
                    new RoutingRequest("SQL to execute: " + sql, questions, routingSettings.model()));
            double yesProb = result.getNoul("destructive_sql");
            logger.info(String.format("JEV noul(destructive_sql)=%.3f for SQL: %s", yesProb,
                    sql.length() > 80 ? sql.substring(0, 80) + "..." : sql));
            return yesProb >= 0.5;
        } catch (Exception e) {
            logger.warning("JEV noul check failed: " + e.getMessage() + " — defaulting to approval required");
            return true;
        }
    }

    /**
     * Prompts the user for confirmation in the CLI.
     *
     * @return {@code true} if the user approved
     */
    private boolean requestApproval(String description, String reason) {
        System.out.println("\n" + YELLOW + BOLD + "[Approval required] " + RESET + description);
        if (reason != null && !reason.isEmpty()) {
            System.out.println(CYAN + "  Reason: " + reason + RESET);
        }
        System.out.print(YELLOW + "Proceed? [y/N]: " + RESET);
        System.out.flush();
        try {
            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(System.in));
            String answer = reader.readLine();
            return answer != null && (answer.trim().equalsIgnoreCase("y") || answer.trim().equalsIgnoreCase("yes"));
        } catch (java.io.IOException e) {
            return false;
        }
    }

    /**
     * Gate for every action that needs sign-off. Only the interactive terminal has a human to ask,
     * so anywhere else (the HTTP {@code chat} action, SSE clients) the answer is a flat no: the
     * model's output is untrusted input and must not be able to write, run or modify on its own.
     */
    private boolean authorize(boolean cli, String description, String reason) {
        if (!cli) {
            logger.warning("Refused (no interactive approver available): " + description);
            return false;
        }
        return requestApproval(description, reason);
    }

    /** Records a refusal in the conversation so the model knows the action did not happen. */
    private void denyAction(Builders history, String description, boolean cli) {
        if (cli) System.out.println(RED + "[Approval] Denied: " + description + RESET);
        Builder denied = new Builder();
        denied.put("role", "user");
        denied.put("content", "Action denied" + (cli ? " by user" : " (approval is only available in the interactive terminal)")
                + ": " + description);
        history.add(denied);
    }

    /**
     * Resolves a model-supplied path inside the workspace, refusing anything that escapes it
     * (absolute paths elsewhere, {@code ..}, symlinks) and the files that hold secrets or history.
     */
    private java.nio.file.Path resolveInWorkspace(String path) throws ApplicationException {
        try {
            java.nio.file.Path root = workspace.toRealPath();
            java.nio.file.Path target = root.resolve(path).normalize();
            // Resolve symlinks on the deepest existing ancestor so a link cannot point outside.
            java.nio.file.Path probe = target;
            while (probe != null && !java.nio.file.Files.exists(probe)) probe = probe.getParent();
            if (probe == null || !probe.toRealPath().startsWith(root)) {
                throw new ApplicationException("Path is outside the workspace: " + path);
            }
            String name = target.getFileName() == null ? "" : target.getFileName().toString();
            if (name.equals("application.properties") || name.equals(HISTORY_FILE)) {
                throw new ApplicationException("Access to " + name + " is not allowed.");
            }
            return target;
        } catch (java.io.IOException e) {
            throw new ApplicationException("Cannot resolve path: " + path, e);
        }
    }

    private boolean processActions(String responseText, Builders history, boolean cli) {
        boolean executedAny = false;
        if (responseText.contains("```json")) {
            try {
                String jsonPart = responseText.substring(responseText.indexOf("```json") + 7);
                jsonPart = jsonPart.substring(0, jsonPart.indexOf("```")).trim();

                Builders actions = new Builders();
                actions.parse(jsonPart);

                for (int i = 0; i < actions.size(); i++) {
                    Builder action = actions.get(i);
                    String type = action.get("action").toString();

                    try {
                        switch (type) {
                            case "write": {
                                String path = action.get("path").toString();
                                String desc = "write to file: " + path;
                                // write is always destructive
                                if (!authorize(cli, desc, "File writes are irreversible.")) {
                                    denyAction(history, desc, cli);
                                    executedAny = true;
                                    break;
                                }
                                this.write(path, action.get("content").toString());
                                break;
                            }
                            case "read": {
                                String path = action.get("path").toString();
                                String content;
                                try {
                                    content = this.read(path);
                                } catch (ApplicationException e) {
                                    // Tell the model why, rather than leaving it waiting on a result.
                                    content = "Error: " + e.getMessage();
                                }
                                if (content.length() > MAX_READ_CHARS) {
                                    content = content.substring(0, MAX_READ_CHARS)
                                            + "\n...[truncated, file has " + content.length() + " characters]";
                                }
                                if (cli) System.out.println("\n" + MAGENTA + BOLD + "[System] Read file: " + RESET + CYAN + path + RESET);
                                Builder readMsg = new Builder();
                                readMsg.put("role", "user");
                                readMsg.put("content", "File " + path + " contents:\n" + content);
                                history.add(readMsg);
                                executedAny = true;
                                break;
                            }
                            case "exec": {
                                String cmd = action.get("cmd").toString();
                                String desc = "exec: " + cmd;
                                // shell exec always requires approval
                                if (!authorize(cli, desc, "Shell commands can have side-effects.")) {
                                    denyAction(history, desc, cli);
                                    executedAny = true;
                                    break;
                                }
                                this.execute(cmd);
                                break;
                            }
                            case "mcp": {
                                String toolName = action.get("tool").toString();
                                Builder params = action.get("params") != null ? (Builder) action.get("params") : new Builder();
                                String desc = "MCP tool '" + toolName + "' with params: " + params;

                                // Tier 1: static confirm-actions list from typesafe.routing.confirm-actions
                                boolean needsApproval = routingSettings.confirmActions().contains(toolName);

                                // Tier 2: for db/execute, ask JEV semantically whether the SQL is destructive
                                if (!needsApproval && "db/execute".equals(toolName)) {
                                    Object sqlObj = params.get("sql");
                                    String sql = sqlObj != null ? sqlObj.toString() : "";
                                    needsApproval = jevNeedsApproval(sql);
                                }

                                if (needsApproval && !authorize(cli, desc,
                                        "db/execute".equals(toolName)
                                                ? "This SQL is not provably read-only (or JEV flagged it as modifying data)."
                                                : "This operation is listed in typesafe.routing.confirm-actions.")) {
                                    denyAction(history, desc, cli);
                                    executedAny = true;
                                    break;
                                }

                                if (cli) System.out.println("\n" + MAGENTA + BOLD + "[System] Executing MCP tool: " + RESET + CYAN + toolName + RESET + " with params: " + YELLOW + params.toString() + RESET);

                                // Connect lazily — the HTTP server starts on a background thread
                                MCPSpecification.SessionState state = this.mcpClient.getSessionState();
                                if (state == null
                                        || state == MCPSpecification.SessionState.ERROR
                                        || state == MCPSpecification.SessionState.DISCONNECTED) {
                                    this.mcpClient.connect();
                                }

                                Object toolResult;
                                try {
                                    toolResult = this.mcpClient.callTool(toolName, params);
                                } catch (Exception ex) {
                                    logger.log(Level.SEVERE, "MCP tool call failed: " + toolName, ex);
                                    Builder errMsg = new Builder();
                                    errMsg.put("role", "user");
                                    errMsg.put("content", "MCP tool " + toolName + " failed: " + ex.getMessage());
                                    history.add(errMsg);
                                    executedAny = true;
                                    break;
                                }

                                String rawResult = toolResult != null ? toolResult.toString() : "null";
                                // mcpResult is what the old code called it — keep naming consistent
                                String formattedResult;
                                if (rawResult.startsWith("{")) {
                                    try {
                                        Builder resultObj = new Builder();
                                        resultObj.parse(rawResult);
                                        if (resultObj.get("tables") instanceof Builders) {
                                            formattedResult = formatAsTable((Builders) resultObj.get("tables"));
                                        } else if (resultObj.get("rows") instanceof Builders) {
                                            formattedResult = formatAsTable((Builders) resultObj.get("rows"));
                                        } else if (resultObj.get("columns") instanceof Builders) {
                                            formattedResult = formatAsTable((Builders) resultObj.get("columns"));
                                        } else {
                                            formattedResult = rawResult;
                                        }
                                    } catch (Exception e) {
                                        formattedResult = rawResult;
                                    }
                                } else {
                                    formattedResult = rawResult;
                                }

                                if (cli) {
                                    System.out.println(MAGENTA + BOLD + "[System] MCP Tool result:" + RESET);
                                    System.out.println(GREEN + formattedResult + RESET + "\n");
                                }
                                push("MCP Tool " + toolName + " executed. Result: " + rawResult);

                                Builder toolResultMsg = new Builder();
                                toolResultMsg.put("role", "user");
                                toolResultMsg.put("content", "MCP Tool " + toolName + " executed. Result: " + formattedResult);
                                history.add(toolResultMsg);
                                executedAny = true;
                                break;
                            }
                            default:
                                logger.warning("Unknown action type: " + type);
                        }
                    } catch (ApplicationException e) {
                        logger.log(Level.SEVERE, "Failed to execute action: " + type, e);
                    }
                }
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to parse actions from response: " + e.getMessage());
            }
        }
        return executedAny;
    }

    private String formatAsTable(Builders data) {
        if (data == null || data.size() == 0) return "No results.";

        final int MAX_COL_WIDTH = 50;
        java.util.List<String> keys = new java.util.ArrayList<>(data.get(0).keySet());
        java.util.Map<String, Integer> columnWidths = new java.util.HashMap<>();

        for (String key : keys) {
            columnWidths.put(key, Math.min(MAX_COL_WIDTH, key.length()));
        }

        for (int i = 0; i < data.size(); i++) {
            Builder row = data.get(i);
            for (String key : keys) {
                Object valObj = row.get(key);
                String val = valObj != null ? valObj.toString() : "NULL";
                int len = val.length();
                if (len > MAX_COL_WIDTH) len = MAX_COL_WIDTH;
                columnWidths.put(key, Math.max(columnWidths.get(key), len));
            }
        }

        StringBuilder sb = new StringBuilder();
        // Header line
        sb.append("+");
        for (String key : keys) {
            sb.append("-".repeat(columnWidths.get(key) + 2)).append("+");
        }
        sb.append("\n");

        // Header text
        sb.append("|");
        for (String key : keys) {
            String k = key;
            if (k.length() > MAX_COL_WIDTH) k = k.substring(0, MAX_COL_WIDTH - 3) + "...";
            sb.append(" ").append(String.format("%-" + columnWidths.get(key) + "s", k)).append(" |");
        }
        sb.append("\n");

        // Separator
        sb.append("+");
        for (String key : keys) {
            sb.append("-".repeat(columnWidths.get(key) + 2)).append("+");
        }
        sb.append("\n");

        // Rows
        for (int i = 0; i < data.size(); i++) {
            Builder row = data.get(i);
            sb.append("|");
            for (String key : keys) {
                Object valObj = row.get(key);
                String val = valObj != null ? valObj.toString() : "NULL";
                if (val.length() > MAX_COL_WIDTH) {
                    val = val.substring(0, MAX_COL_WIDTH - 3) + "...";
                }
                sb.append(" ").append(String.format("%-" + columnWidths.get(key) + "s", val)).append(" |");
            }
            sb.append("\n");
        }

        // Bottom line
        sb.append("+");
        for (String key : keys) {
            sb.append("-".repeat(columnWidths.get(key) + 2)).append("+");
        }

        return sb.toString();
    }


    @Action(value = "exec", description = "Execute a tool command", options = {
            @Argument(key = "cmd", description = "Command to execute")
    }, mode = Action.Mode.CLI)
    public String execute(String cmd) throws ApplicationException {
        logger.info("Executing tool: " + cmd);
        // This can be used to run other dispatcher commands
        Object result = ApplicationManager.call(cmd, getContext(), Action.Mode.CLI);
        return result != null ? result.toString() : "Success";
    }

    @Action(value = "read", description = "Read a file inside the workspace", options = {
            @Argument(key = "path", description = "File path")
    }, mode = Action.Mode.CLI)
    public String read(String path) throws ApplicationException {
        try {
            java.nio.file.Path filePath = resolveInWorkspace(path);
            if (!java.nio.file.Files.exists(filePath)) {
                return "Error: File does not exist: " + path;
            }
            return java.nio.file.Files.readString(filePath);
        } catch (java.io.IOException e) {
            throw new ApplicationException("Failed to read file: " + path, e);
        }
    }

    @Action(value = "write", description = "Write content to a file inside the workspace", options = {
            @Argument(key = "path", description = "File path"),
            @Argument(key = "content", description = "Content to write")
    }, mode = Action.Mode.CLI)
    public String write(String path, String content) throws ApplicationException {
        try {
            java.nio.file.Path filePath = resolveInWorkspace(path);
            java.nio.file.Path parent = filePath.getParent();
            if (parent != null && !java.nio.file.Files.exists(parent)) {
                java.nio.file.Files.createDirectories(parent);
            }
            java.nio.file.Files.writeString(filePath, content);
            return "Successfully wrote to " + path;
        } catch (java.io.IOException e) {
            throw new ApplicationException("Failed to write to file: " + path, e);
        }
    }

    @Action(value = "push", description = "Push progress to SSE", options = {
            @Argument(key = "msg", description = "Message to push")
    })
    public void push(String msg) {
        String sessionId = getContext().getId();
        if (sessionId != null) {
            Builder builder = new Builder();
            builder.put("status", "progress");
            builder.put("message", msg);
            SSEPushManager.getInstance().push(sessionId, builder);
        }
    }
}