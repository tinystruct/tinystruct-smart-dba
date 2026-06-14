package org.tinystruct.app;

import org.tinystruct.AbstractApplication;
import org.tinystruct.ApplicationContext;
import org.tinystruct.ApplicationException;
import org.tinystruct.data.component.Builder;
import org.tinystruct.data.component.Builders;
import org.tinystruct.http.SSEPushManager;
import org.tinystruct.net.URLRequest;
import org.tinystruct.net.handlers.HTTPHandler;
import org.tinystruct.system.ApplicationManager;
import org.tinystruct.system.HttpServer;
import org.tinystruct.system.annotation.Action;
import org.tinystruct.system.annotation.Argument;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.tinystruct.mcp.MCPClient;

/**
 * Agent application for autonomous coding tasks.
 */
public class SmartDBA extends AbstractApplication {
    private static final Logger logger = Logger.getLogger(SmartDBA.class.getName());
    private static final String DEFAULT_OPENAI_API_URL = "https://api.openai.com/v1/chat/completions";
    private static final String DEFAULT_GEMINI_API_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent";
    private String apiKey;
    private String apiUrl;
    private String skill;
    private boolean virtualTerminal;
    private MCPClient mcpClient;
    private static final String HISTORY_FILE = ".agent_history.json";
    
    private static final String SYSTEM_PROMPT = "You are SmartDBA, an autonomous database agent. You have access to the following MCP database tools:\n" +
            "- list-tables\n" +
            "- describe: requires param 'table'\n" +
            "- query: requires params 'table', 'where', optional 'limit'\n" +
            "- insert: requires params 'table', 'data'\n" +
            "- update: requires params 'table', 'data', 'where'\n" +
            "- delete: requires params 'table', 'where'\n" +
            "- execute: requires param 'sql'\n" +
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
    private static final String KEYWORD = "\u001b[35m"; // Magenta
    private static final String STRING = "\u001b[32m";  // Green
    private static final String COMMENT = "\u001b[36m"; // Cyan
    private static final String NUMBER = "\u001b[33m";  // Yellow
    private static final String CLASS = "\u001b[34m";   // Blue

    @Override
    public void init() {
        this.setTemplateRequired(false);
        this.apiKey = this.getConfiguration().get("agent.api_key");
        this.apiUrl = this.getConfiguration().get("agent.api_url");
        if (this.apiUrl == null || this.apiUrl.isEmpty()) {
            this.apiUrl = DEFAULT_GEMINI_API_URL;
        }

        ApplicationManager.install(new SmartDBAServer());
        ApplicationManager.install(new HttpServer());
        new Thread(() -> {
        try {
            ApplicationManager.call("start", new ApplicationContext(), Action.Mode.CLI);
        } catch (ApplicationException e) {
            logger.log(Level.WARNING, "Error starting SmartDBA", e);
            throw new RuntimeException(e);
        }}).start();

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
        
        String mcpUrl = this.getConfiguration().get("mcp.server.url");
        if (mcpUrl == null || mcpUrl.isEmpty()) {
            mcpUrl = "http://localhost:8080/";
        }
        String mcpToken = this.getConfiguration().get("mcp.auth.token");
        this.mcpClient = new MCPClient(mcpUrl, mcpToken);
        try {
            this.mcpClient.connect();
        } catch (java.io.IOException e) {
            logger.warning("Failed to connect to MCP: " + e.getMessage());
        }
    }

    @Override
    public String version() {
        return "1.0.0";
    }

    @Action(value = "agent/chat", description = "Chat with the agent", options = {
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

        return internalChat(message);
    }

    @Action(value = "agent/interactive", description = "Start an interactive chat session", mode = Action.Mode.CLI)
    public void interactive() throws ApplicationException {
        System.out.println("Starting interactive Agent session. Type 'exit' or 'quit' to leave.");
        java.util.Scanner scanner = new java.util.Scanner(System.in);
        while (true) {
            System.out.print("You > ");
            if (!scanner.hasNextLine()) break;
            String input = scanner.nextLine().trim();
            if (input.equalsIgnoreCase("exit") || input.equalsIgnoreCase("quit")) {
                System.out.println("Goodbye!");
                break;
            }
            if (input.isEmpty()) continue;

            String response = internalChat(input);
            System.out.println("\nAgent > " + response + "\n");
        }
    }

    private String highlight(String text) {
        if (!virtualTerminal && System.getProperty("os.name").toLowerCase().contains("win")) {
            // Basic check for Windows virtual terminal support
            // (Dispatcher uses JNA for this, but we'll try to just output codes)
            virtualTerminal = true;
        }

        StringBuilder sb = new StringBuilder();
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("```java(.*?)```", java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher matcher = pattern.matcher(text);
        int lastEnd = 0;
        while (matcher.find()) {
            sb.append(text, lastEnd, matcher.start());
            String code = matcher.group(1);
            sb.append("```java").append(highlightJava(code)).append("```");
            lastEnd = matcher.end();
        }
        sb.append(text.substring(lastEnd));
        return sb.toString();
    }

    private String highlightJava(String code) {
        String[] keywords = {"abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const", "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native", "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void", "volatile", "while", "true", "false", "null"};
        
        // Add line numbers first
        StringBuilder sb = new StringBuilder();
        String[] lines = code.split("\n");
        for (int i = 0; i < lines.length; i++) {
            sb.append(String.format("%3d: ", i + 1)).append(lines[i]).append("\n");
        }
        code = sb.toString();

        // Strings
        code = code.replaceAll("(\".*?\")", STRING + "$1" + RESET);
        
        // Keywords
        for (String kw : keywords) {
            code = code.replaceAll("\\b" + kw + "\\b", KEYWORD + kw + RESET);
        }
        
        // Comments
        code = code.replaceAll("(//.*)", COMMENT + "$1" + RESET);
        
        // Numbers
        code = code.replaceAll("\\b(\\d+)\\b", NUMBER + "$1" + RESET);
        
        return code;
    }

    private String internalChat(String message) throws ApplicationException {
        if (this.apiKey == null || this.apiKey.isEmpty()) {
            return "Error: API key not configured. Please set 'agent.api_key' in application.properties.";
        }

        // 1. Load history
        Builders history = loadHistory();
        
        // Add user message
        Builder userMsg = new Builder();
        userMsg.put("role", "user");
        userMsg.put("content", message);
        history.add(userMsg);

        // 2. Call the API
        try {
            boolean isGemini = this.apiUrl.contains("generativelanguage.googleapis.com");
            URL url;
            Builder payload;
            if (isGemini) {
                url = URI.create(this.apiUrl + "?key=" + this.apiKey).toURL();
                payload = prepareGeminiPayload(history);
            } else {
                url = URI.create(this.apiUrl).toURL();
                payload = prepareOpenAIPayload(history);
            }

            URLRequest request = new URLRequest(url);
            request.setMethod("POST")
                    .setHeader("Content-Type", "application/json");
            
            if (!isGemini) {
                request.setHeader("Authorization", "Bearer " + this.apiKey);
            }
            
            request.setBody(payload.toString());

            HTTPHandler handler = new HTTPHandler();
            var response = handler.handleRequest(request);

            if (response.getStatusCode() == 200) {
                Builder result = new Builder();
                result.parse(response.getBody());
                
                String responseText;
                if (isGemini) {
                    responseText = parseGeminiResponse(result);
                } else {
                    responseText = parseOpenAIResponse(result);
                }

                // Add assistant response to history
                Builder assistantMsg = new Builder();
                assistantMsg.put("role", "assistant");
                assistantMsg.put("content", responseText);
                history.add(assistantMsg);
                
                // Save history
                saveHistory(history);

                // 3. Parse and execute actions
                processActions(responseText);

                return highlight(responseText);
            } else {
                return "Error: API returned status " + response.getStatusCode() + "\n" + response.getBody();
            }
        } catch (MalformedURLException e) {
            throw new ApplicationException("Invalid API URL: " + this.apiUrl, e);
        }
    }

    @Action(value = "agent/clear", description = "Clear chat history")
    public String clear() {
        try {
            java.nio.file.Files.deleteIfExists(java.nio.file.Paths.get(HISTORY_FILE));
            return "History cleared.";
        } catch (java.io.IOException e) {
            return "Error clearing history: " + e.getMessage();
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
        payload.put("model", "gpt-4-turbo-preview");
        
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

    private String parseOpenAIResponse(Builder result) throws ApplicationException {
        Builders choices = (Builders) result.get("choices");
        if (choices != null && choices.size() > 0) {
            Builder firstChoice = choices.get(0);
            Builder msgObj = (Builder) firstChoice.get("message");
            return msgObj.get("content").toString();
        }
        return "Error: No response from OpenAI.";
    }

    private void processActions(String responseText) {
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
                            case "write":
                                this.write(action.get("path").toString(), action.get("content").toString());
                                break;
                            case "read":
                                this.read(action.get("path").toString());
                                break;
                            case "exec":
                                this.execute(action.get("cmd").toString());
                                break;
                            case "mcp":
                                String toolName = action.get("tool").toString();
                                Builder params = action.get("params") != null ? (Builder) action.get("params") : new Builder();
                                System.out.println("\n[System] Executing MCP tool: " + toolName + " with params: " + params.toString());
                                Object mcpResult = this.mcpClient.callTool(toolName, params);
                                System.out.println("[System] MCP Tool result: " + mcpResult + "\n");
                                push("MCP Tool " + toolName + " executed. Result: " + mcpResult);
                                break;
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
    }

    private String parseGeminiResponse(Builder result) throws ApplicationException {
        // Gemini response format: {"candidates": [{"content": {"parts": [{"text": "..."}]}}]}
        Builders candidates = (Builders) result.get("candidates");
        if (candidates != null && candidates.size() > 0) {
            Builder firstCandidate = candidates.get(0);
            Builder content = (Builder) firstCandidate.get("content");
            if (content != null) {
                Builders parts = (Builders) content.get("parts");
                if (parts != null && parts.size() > 0) {
                    return parts.get(0).get("text").toString();
                }
            }
        }
        return "Error: No response from Gemini.";
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

    @Action(value = "read", description = "Read a file", options = {
            @Argument(key = "path", description = "File path")
    })
    public String read(String path) throws ApplicationException {
        try {
            java.nio.file.Path filePath = java.nio.file.Paths.get(path);
            if (!java.nio.file.Files.exists(filePath)) {
                return "Error: File does not exist: " + path;
            }
            return java.nio.file.Files.readString(filePath);
        } catch (java.io.IOException e) {
            throw new ApplicationException("Failed to read file: " + path, e);
        }
    }

    @Action(value = "write", description = "Write content to a file", options = {
            @Argument(key = "path", description = "File path"),
            @Argument(key = "content", description = "Content to write")
    })
    public String write(String path, String content) throws ApplicationException {
        try {
            java.nio.file.Path filePath = java.nio.file.Paths.get(path);
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
