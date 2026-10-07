package com.example.demo.service;

import com.example.demo.config.AiCompatProperties;
import com.example.demo.config.SessionMemoryProperties;
import com.example.demo.dto.SessionMemory;
import com.example.demo.dto.QARequest;
import com.example.demo.dto.QAResponse;
import com.example.demo.model.AiConversation;
import com.example.demo.model.AiMessage;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class QAService {

    private static final String WEB_SEARCH_TOOL_NAME = "web_search";
    private static final String MANUAL_SEARCH_TOOL_NAME = "manual_search";
    private static final int MAX_TOOL_ROUNDS = 2;

    private static final Pattern ANSWER_SECTION_PATTERN =
            Pattern.compile("^[【\\[]?(结论|操作步骤|注意事项)[】\\]]?[：:]?\\s*(.*)$");
    private static final Pattern MODEL_TOKEN_PATTERN =
            Pattern.compile("(?i)\\b[A-Z]{0,4}\\d{1,6}[A-Z0-9-]*\\b");

    private static final List<String> APPLIANCE_KEYWORDS = List.of(
            "家电", "空调", "冰箱", "洗衣机", "热水器", "油烟机", "烟机", "灶",
            "燃气灶", "净水器", "洗碗机", "微波炉", "烤箱", "电饭煲", "电磁炉",
            "吸尘器", "扫地机器人", "空气净化器", "除湿机", "风扇", "电视",
            "遥控器", "咖啡机", "净化器");

    private static final List<String> BRAND_KEYWORDS = List.of(
            "海尔", "美的", "格力", "小米", "TCL", "海信", "西门子", "松下", "博世",
            "日立", "三菱", "华凌", "奥克斯", "方太", "老板", "九阳", "苏泊尔",
            "容声", "美菱", "卡萨帝", "统帅", "Haier", "Midea", "Gree", "Hisense",
            "Panasonic", "Bosch", "Siemens", "TCL", "Hitachi");

    private static final List<String> OPERATION_KEYWORDS = List.of(
            "怎么", "如何", "单脱水", "脱水", "单洗", "单漂", "启动", "设置",
            "模式", "功能", "按键", "步骤", "使用", "操作", "童锁", "预约",
            "解锁", "连接", "配网", "清洗", "清洁", "保养", "安装");

    private static final List<String> TROUBLESHOOTING_KEYWORDS = List.of(
            "故障", "故障码", "故障代码", "错误代码", "报码", "报错", "维修",
            "打不开", "不启动", "不制冷", "不加热", "不脱水", "漏水", "异响",
            "报警", "e1", "e2", "e3", "f1", "f2", "f3", "售后", "客服");

    private static final List<String> PRODUCT_KEYWORDS = List.of(
            "参数", "规格", "尺寸", "功率", "能效", "容量", "区别", "对比", "型号",
            "重量", "功能", "配置");

    private static final String BASE_SYSTEM_PROMPT = """
            你是智能家电助手。
            回答要求：
            1. 优先依据给定的说明书证据回答，不要编造不存在的参数、按钮、步骤或页码。
            2. 如果证据不足，就明确说明缺少信息，并给出保守、可执行且安全的建议。
            3. 如果识别结果或外部搜索结果存在不确定性，要直接指出，不要装作确认无误。
            4. 涉及拆机、高压、电路、制冷剂等风险操作时，提醒联系专业人员。
            5. 你必须严格按以下标题输出：结论、操作步骤、注意事项。
            6. 不要输出其他标题，不要输出“依据说明”之类额外小节。
            """;

    private final AiCompatProperties aiCompatProperties;
    private final OpenAiCompatibleClient openAiCompatibleClient;
    private final SearxngSearchService searxngSearchService;
    private final VisionRecognitionService visionRecognitionService;
    private final ManualRetrievalService manualRetrievalService;
    private final AiConversationService aiConversationService;
    private final PersonalizedContextService personalizedContextService;
    private final PythonAgentClient pythonAgentClient;
    private final ObjectMapper objectMapper;
    private final SessionMemoryProperties memoryProperties;
    private final AiTurnLedgerService turnLedger;
    private final AiConversationMemoryService conversationMemory;

    public QAResponse answer(QARequest req) {
        if (req == null || !memoryProperties.isRuntimeEnabled()) return answerLegacy(req);
        if (!StringUtils.hasText(req.getQuery())) return memoryError(req, "query_required");
        if (!StringUtils.hasText(req.getRequestId())) req.setRequestId(java.util.UUID.randomUUID().toString());
        if (Boolean.FALSE.equals(req.getPersistHistory()) || !StringUtils.hasText(req.getUserId())) {
            req.setPersistHistory(false); req.setMemory(null); req.setHistory(List.of()); req.setMessages(List.of());
            req.setConversationId(null);
            QAResponse response = answerLegacy(req); response.setRequestId(req.getRequestId());
            return response;
        }
        AiTurnLedgerService.Started started = null;
        java.util.concurrent.ScheduledExecutorService heartbeat = null;
        boolean dispatched = false;
        try {
            if (pythonAgentClient.isEnabled()) pythonAgentClient.normalizeRequestContext(req);
            started = turnLedger.begin(req);
            if (started.cached() != null) return started.cached();
            final var running = started.request();
            heartbeat = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "session-memory-lease"); thread.setDaemon(true); return thread;
            });
            heartbeat.scheduleAtFixedRate(() -> {
                try { turnLedger.renew(running); } catch (Exception ignored) { /* Fail closed at the next checkpoint. */ }
            }, 10, 10, java.util.concurrent.TimeUnit.SECONDS);
            req.setMemory(conversationMemory.prepare(started));
            req.setHistory(List.of()); req.setMessages(List.of());
            SessionMemory.PythonResponse result;
            if (pythonAgentClient.isEnabled()) {
                try { dispatched = true; result = pythonAgentClient.answerWithMemory(req); }
                catch (Exception ex) {
                    // Only a confirmed connection refusal means Python could not
                    // dispatch a model/tool. Read timeouts and HTTP failures are unknown.
                    if (!pythonAgentClient.isFallbackToJava() || !connectionRefused(ex)) throw ex;
                    dispatched = false;
                    result = internalResponse(answerLegacy(req));
                }
            } else { result = internalResponse(answerLegacy(req)); }
            result.setMemoryStatus(req.getMemory().memoryStatus());
            return turnLedger.finish(running, result);
        } catch (Exception ex) {
            if (started != null && started.cached() == null) {
                if (dispatched) turnLedger.markUnknown(started.request());
                else {
                    // A pre-dispatch validation/summary failure is a known safe
                    // terminal answer, and still completes a recoverable turn.
                    try { return turnLedger.finish(started.request(), internalResponse(memoryError(req,
                            ex instanceof SessionMemoryFault ? ex.getMessage() : "memory_prepare_failed"))); }
                    catch (Exception ignored) { turnLedger.markUnknown(started.request()); }
                }
            }
            return memoryError(req, ex instanceof SessionMemoryFault ? ex.getMessage() : "request_result_unknown");
        } finally { if (heartbeat != null) heartbeat.shutdownNow(); }
    }

    private SessionMemory.PythonResponse internalResponse(QAResponse response) {
        return objectMapper.convertValue(response, SessionMemory.PythonResponse.class);
    }
    private QAResponse memoryError(QARequest req, String code) {
        QAResponse response = createBaseResponse(); response.setStatus("error"); response.setError(code);
        response.setRequestId(req != null ? req.getRequestId() : null);
        response.setConversationId(req != null ? req.getConversationId() : null);
        response.setMemoryStatus("degraded"); response.setInsufficientContext(true);
        response.setAnswerText(switch (code) {
            case "conversation_busy", "request_in_progress" -> "当前会话仍有请求处理中，请稍后查看，不要重复提交。";
            case "conversation_not_found" -> "会话不存在或不属于当前用户，请重新选择会话。";
            case "context_budget_exceeded" -> "当前上下文超过模型输入预算，请新建会话；本轮没有继续调用工具。";
            case "query_required" -> "请先输入问题。";
            case "request_id_conflict" -> "请求标识已用于其他内容，请勿将它用于新问题。";
            default -> "本次请求暂无法继续，结果状态需要核实，请勿盲目重复提交写操作。";
        });
        return response;
    }
    private static boolean connectionRefused(Throwable ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause())
            if (cause instanceof java.net.ConnectException) return true;
        return false;
    }

    private QAResponse answerLegacy(QARequest req) {
        QAResponse response = createBaseResponse();

        boolean hasImage = visionRecognitionService.hasImage(req);
        String query = buildEffectiveQuery(req, hasImage);
        if (!StringUtils.hasText(query)) {
            response.setError("query_required");
            response.setAnswerText(hasImage
                    ? "请先输入问题，或确认图片与识别选项已正确提供。"
                    : "请先输入你的问题，我再继续帮你解答。");
            return response;
        }

        if (pythonAgentClient.isEnabled()) {
            try {
                pythonAgentClient.normalizeRequestContext(req);
            } catch (IllegalArgumentException ex) {
                if ("manual_context_conflict".equals(ex.getMessage())
                        || "invalid_manual_context".equals(ex.getMessage())
                        || "invalid_product_context".equals(ex.getMessage())) {
                    response.setStatus("error");
                    response.setError(ex.getMessage());
                    response.setAnswerText("当前说明书或设备信息无法确认，请重新选择后再试。");
                    return response;
                }
                throw ex;
            }
        }

        AiConversation conversation = req != null && req.getMemory() != null
                ? turnLedger.owned(req.getUserId(), req.getConversationId()) : aiConversationService.resolveConversation(req);
        List<AiMessage> historyMessages = conversation != null
                && (req == null || req.getMemory() == null) ? aiConversationService.getRecentHistoryMessages(conversation.getId())
                : List.of();

        if (pythonAgentClient.isEnabled() && (req == null || req.getMemory() == null)) {
            try {
                QAResponse pythonResponse = pythonAgentClient.answer(req, historyMessages);
                return persistPythonResponse(pythonResponse, conversation, req);
            } catch (Exception ex) {
                if (ex instanceof IllegalArgumentException
                        && ("manual_context_conflict".equals(ex.getMessage())
                        || "invalid_manual_context".equals(ex.getMessage())
                        || "invalid_product_context".equals(ex.getMessage()))) {
                    response.setStatus("error");
                    response.setError(ex.getMessage());
                    response.setAnswerText("当前说明书或设备信息无法确认，请重新选择后再试。");
                    return persistPythonResponse(response, conversation, req);
                }
                if (!pythonAgentClient.isFallbackToJava()) {
                    response.setStatus("error");
                    response.setTarget("deepseek");
                    response.setError("python_agent_unavailable");
                    response.setAnswerText("Python Agent服务暂时不可用：" + ex.getMessage());
                    return persistPythonResponse(response, conversation, req);
                }
            }
        }

        String target = resolveTarget(req != null ? req.getTarget() : null);
        response.setTarget(target);

        AiCompatProperties.TargetConfig targetConfig = aiCompatProperties.getTargets().get(target);
        if (targetConfig == null
                || !StringUtils.hasText(targetConfig.getBaseUrl())
                || !StringUtils.hasText(targetConfig.getModel())) {
            response.setError("target_not_configured");
            response.setAnswerText("AI target is not configured: " + target);
            return response;
        }

        try {
            QAResponse.VisionResult vision = null;
            if (Boolean.TRUE.equals(req != null ? req.getEnableImageRecognition() : null) && hasImage) {
                vision = visionRecognitionService.recognize(
                        targetConfig,
                        req,
                        query,
                        aiCompatProperties.getTemperature(),
                        aiCompatProperties.getTimeoutSeconds());
                response.setVision(vision);
                query = buildQueryWithVisionContext(query, vision);
            }

            PersonalizedContextService.PersonalizedContext personalizedContext =
                    personalizedContextService.build(req, conversation, historyMessages, vision, query);
            String contextualQuery = personalizedContextService.buildContextualQuery(query, personalizedContext);

            boolean enableManualSearch = StringUtils.hasText(resolveManualLookupId(req));
            boolean enableWebSearch = req != null && Boolean.TRUE.equals(req.getEnableWebSearch());

            if (enableManualSearch || enableWebSearch) {
                response = answerWithTools(
                        req,
                        query,
                        contextualQuery,
                        targetConfig,
                        response,
                        historyMessages,
                        enableManualSearch,
                        enableWebSearch,
                        personalizedContext);
            } else {
                response = answerWithoutTools(req, query, targetConfig, response, historyMessages, personalizedContext);
            }

            if ("ok".equalsIgnoreCase(response.getStatus())) {
                response.setStructuredAnswer(buildStructuredAnswer(
                        response.getAnswerText(),
                        Boolean.TRUE.equals(response.getInsufficientContext())));
            }
        } catch (ManualRetrievalService.ManualRetrievalException ex) {
            response.setModel(resolveToolModel(targetConfig));
            response.setError(ex.getErrorCode());
            response.setAnswerText(ex.getMessage());
        } catch (Exception ex) {
            response.setModel(resolveToolModel(targetConfig));
            response.setError("upstream_failed");
            response.setAnswerText("AI request failed: " + ex.getMessage());
        }

        if ("ok".equalsIgnoreCase(response.getStatus()) && conversation != null && req.getMemory() == null) {
            AiConversation savedConversation = aiConversationService.appendQaTurn(
                    conversation,
                    req,
                    response.getAnswerText(),
                    response.getStructuredAnswer(),
                    Boolean.TRUE.equals(response.getInsufficientContext()),
                    response.getSources() != null ? response.getSources() : List.of());
            response.setConversationId(savedConversation != null ? savedConversation.getId() : conversation.getId());
        } else if (conversation != null) {
            response.setConversationId(conversation.getId());
        }
        return response;
    }

    private QAResponse persistPythonResponse(QAResponse response, AiConversation conversation, QARequest req) {
        if (response == null) {
            response = createBaseResponse();
            response.setError("empty_python_agent_response");
            response.setAnswerText("Python Agent没有返回有效结果。");
        }
        if ("ok".equalsIgnoreCase(response.getStatus()) && conversation != null && req.getMemory() == null) {
            AiConversation savedConversation = aiConversationService.appendQaTurn(
                    conversation,
                    req,
                    response.getAnswerText(),
                    response.getStructuredAnswer(),
                    Boolean.TRUE.equals(response.getInsufficientContext()),
                    response.getSources() != null ? response.getSources() : List.of());
            response.setConversationId(savedConversation != null ? savedConversation.getId() : conversation.getId());
        } else if (conversation != null) {
            response.setConversationId(conversation.getId());
        }
        return response;
    }

    private QAResponse answerWithoutTools(
            QARequest req,
            String query,
            AiCompatProperties.TargetConfig targetConfig,
            QAResponse response,
            List<AiMessage> historyMessages,
            PersonalizedContextService.PersonalizedContext personalizedContext) {

        List<OpenAiCompatibleClient.ChatMessage> messages = buildPlainMessages(historyMessages, req, query, personalizedContext);
        OpenAiCompatibleClient.ChatResult result = javaModelCall(req,
                targetConfig,
                null,
                messages,
                null,
                null,
                aiCompatProperties.getTemperature(),
                aiCompatProperties.getTimeoutSeconds());

        response.setStatus("ok");
        response.setModel(result.getModel());
        response.setUsedWebSearch(Boolean.FALSE);
        response.setInsufficientContext(Boolean.FALSE);
        response.setSourceFragmentIds(List.of());
        response.setSources(List.of());
        response.setAnswerText(defaultAnswerText(result.getContent()));
        return response;
    }

    private QAResponse answerWithTools(
            QARequest req,
            String query,
            String contextualQuery,
            AiCompatProperties.TargetConfig targetConfig,
            QAResponse response,
            List<AiMessage> historyMessages,
            boolean enableManualSearch,
            boolean enableWebSearch,
            PersonalizedContextService.PersonalizedContext personalizedContext) {

        String toolModel = resolveToolModel(targetConfig);
        String manualLookupId = resolveManualLookupId(req);
        List<OpenAiCompatibleClient.ChatMessage> messages = buildToolMessages(
                historyMessages, req, query, enableWebSearch, enableManualSearch, personalizedContext);

        List<OpenAiCompatibleClient.ToolSpec> tools = new ArrayList<>();
        if (enableManualSearch) {
            tools.add(buildManualSearchTool());
        }
        if (enableWebSearch) {
            tools.add(buildWebSearchTool());
        }

        Map<String, QAResponse.SourceFragment> sourcesById = new LinkedHashMap<>();
        Set<String> sourceFragmentIds = new LinkedHashSet<>();
        boolean usedWebSearch = false;
        boolean manualToolUsed = false;
        boolean insufficientContext = false;

        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            OpenAiCompatibleClient.ChatResult latestResult = javaModelCall(req,
                    targetConfig,
                    toolModel,
                    messages,
                    tools,
                    "auto",
                    aiCompatProperties.getTemperature(),
                    aiCompatProperties.getTimeoutSeconds());

            if (latestResult.getToolCalls() == null || latestResult.getToolCalls().isEmpty()) {
                response.setStatus("ok");
                response.setModel(latestResult.getModel());
                response.setUsedWebSearch(usedWebSearch);
                response.setInsufficientContext(insufficientContext || (enableManualSearch && sourceFragmentIds.isEmpty()));
                response.setSourceFragmentIds(new ArrayList<>(sourceFragmentIds));
                response.setSources(new ArrayList<>(sourcesById.values()));
                response.setAnswerText(defaultAnswerText(latestResult.getContent()));
                return response;
            }

            messages.add(OpenAiCompatibleClient.ChatMessage.assistantWithToolCalls(
                    latestResult.getContent(),
                    latestResult.getToolCalls()));

            boolean executedAnyTool = false;
            for (OpenAiCompatibleClient.ToolCall toolCall : latestResult.getToolCalls()) {
                checkJavaExecution(req);
                if (toolCall == null || toolCall.getFunction() == null) {
                    continue;
                }

                String toolName = trimToNull(toolCall.getFunction().getName());
                String toolPayload;
                if (MANUAL_SEARCH_TOOL_NAME.equals(toolName) && StringUtils.hasText(manualLookupId)) {
                    manualToolUsed = true;
                    ManualToolArguments arguments = parseManualToolArguments(contextualQuery, toolCall.getFunction().getArguments());
                    ManualRetrievalService.RetrievalResult retrieval = manualRetrievalService.retrieve(
                            manualLookupId,
                            arguments.getQuery(),
                            aiCompatProperties.getTimeoutSeconds(),
                            arguments.getTopK());
                    insufficientContext = insufficientContext || retrieval.isInsufficientContext();
                    mergeManualSources(sourceFragmentIds, sourcesById, retrieval.getFragments());
                    toolPayload = buildManualSearchResultPayload(arguments, retrieval);
                    executedAnyTool = true;
                } else if (WEB_SEARCH_TOOL_NAME.equals(toolName)) {
                    ToolArguments arguments = parseToolArguments(contextualQuery, toolCall.getFunction().getArguments());
                    SearxngSearchService.SearchRequest searchRequest = buildSearchRequest(contextualQuery, arguments);
                    List<SearxngSearchService.SearchResult> searchResults = searxngSearchService.search(searchRequest);
                    usedWebSearch = usedWebSearch || !searchResults.isEmpty();
                    mergeWebSources(sourcesById, searchResults);
                    toolPayload = buildToolResultPayload(searchRequest, searchResults);
                    executedAnyTool = true;
                } else {
                    toolPayload = "{\"error\":\"unsupported_tool\"}";
                }

                messages.add(OpenAiCompatibleClient.ChatMessage.toolResponse(toolCall.getId(), toolPayload));
            }

            if (!executedAnyTool) {
                break;
            }
        }

        OpenAiCompatibleClient.ChatResult finalResult = javaModelCall(req,
                targetConfig,
                toolModel,
                messages,
                null,
                null,
                aiCompatProperties.getTemperature(),
                aiCompatProperties.getTimeoutSeconds());

        response.setStatus("ok");
        response.setModel(finalResult.getModel());
        response.setUsedWebSearch(usedWebSearch);
        response.setInsufficientContext(insufficientContext || (enableManualSearch && sourceFragmentIds.isEmpty()));
        response.setSourceFragmentIds(new ArrayList<>(sourceFragmentIds));
        response.setSources(new ArrayList<>(sourcesById.values()));
        response.setAnswerText(defaultAnswerText(finalResult.getContent(), manualToolUsed));
        return response;
    }

    private void checkJavaExecution(QARequest req) {
        if (req.getMemory() != null && !turnLedger.check(req.getUserId(), req.getConversationId(),
                req.getRequestId(), req.getMemory().executionVersion()))
            throw new SessionMemoryFault("execution_qualification_lost");
    }
    private OpenAiCompatibleClient.ChatResult javaModelCall(QARequest req,
            AiCompatProperties.TargetConfig target, String model, List<OpenAiCompatibleClient.ChatMessage> messages,
            List<OpenAiCompatibleClient.ToolSpec> tools, String choice, double temperature, int timeout) {
        if (req.getMemory() == null) return openAiCompatibleClient.chatWithTools(target, model, messages, tools, choice, temperature, timeout);
        checkJavaExecution(req);
        try {
            Map<String, Object> input = new LinkedHashMap<>(); input.put("messages", messages); input.put("tools", tools == null ? List.of() : tools);
            if (objectMapper.writeValueAsBytes(input).length > memoryProperties.getMainInputBudget())
                throw new SessionMemoryFault("context_budget_exceeded");
        } catch (SessionMemoryFault ex) { throw ex; }
        catch (Exception ex) { throw new SessionMemoryFault("memory_serialization_failed"); }
        return openAiCompatibleClient.chatWithTools(target, model, messages, tools, choice, temperature, timeout, memoryProperties.getAnswerReserve());
    }

    private List<OpenAiCompatibleClient.ChatMessage> buildPlainMessages(
            List<AiMessage> historyMessages,
            QARequest req,
            String query,
            PersonalizedContextService.PersonalizedContext personalizedContext) {
        List<OpenAiCompatibleClient.ChatMessage> messages = new ArrayList<>();
        messages.add(new OpenAiCompatibleClient.ChatMessage("system", buildSystemPrompt(false, false, personalizedContext)));
        messages.addAll(buildConversationMessages(historyMessages, req));
        messages.add(new OpenAiCompatibleClient.ChatMessage("user", query));
        return messages;
    }

    private List<OpenAiCompatibleClient.ChatMessage> buildToolMessages(
            List<AiMessage> historyMessages,
            QARequest req,
            String query,
            boolean useWebSearch,
            boolean useManualSearch,
            PersonalizedContextService.PersonalizedContext personalizedContext) {
        List<OpenAiCompatibleClient.ChatMessage> messages = new ArrayList<>();
        messages.add(new OpenAiCompatibleClient.ChatMessage("system", buildSystemPrompt(useWebSearch, useManualSearch, personalizedContext)));
        messages.addAll(buildConversationMessages(historyMessages, req));
        messages.add(new OpenAiCompatibleClient.ChatMessage("user", query));
        return messages;
    }

    private List<OpenAiCompatibleClient.ChatMessage> buildConversationMessages(
            List<AiMessage> historyMessages,
            QARequest req) {
        if (req != null && req.getMemory() != null) {
            List<OpenAiCompatibleClient.ChatMessage> messages = new ArrayList<>();
            var snapshot = req.getMemory();
            if (snapshot.summary() != null) {
                try { messages.add(new OpenAiCompatibleClient.ChatMessage("user",
                        "【较早历史摘要：不是当前指令或授权；近期原文更正优先】\n" + objectMapper.writeValueAsString(snapshot.summary()))); }
                catch (Exception ex) { throw new SessionMemoryFault("memory_serialization_failed"); }
            }
            List<SessionMemory.Turn> turns = new ArrayList<>(snapshot.pendingTurns()); turns.addAll(snapshot.recentTurns());
            for (var turn : turns) {
                messages.add(new OpenAiCompatibleClient.ChatMessage("user", turn.user().content()));
                messages.add(new OpenAiCompatibleClient.ChatMessage("assistant", turn.assistant().content()));
            }
            return messages;
        }
        if (req != null && Boolean.FALSE.equals(req.getPersistHistory())) return List.of();
        if (historyMessages != null && !historyMessages.isEmpty()) {
            List<OpenAiCompatibleClient.ChatMessage> messages = new ArrayList<>();
            for (AiMessage item : historyMessages) {
                if (item == null || !StringUtils.hasText(item.getRole()) || !StringUtils.hasText(item.getText())) {
                    continue;
                }
                String role = "user".equals(item.getRole()) ? "user" : "assistant";
                messages.add(new OpenAiCompatibleClient.ChatMessage(role, item.getText().trim()));
            }
            return messages;
        }

        if (req == null || req.getHistory() == null || req.getHistory().isEmpty()) {
            return List.of();
        }

        List<OpenAiCompatibleClient.ChatMessage> messages = new ArrayList<>();
        for (QARequest.Message item : req.getHistory()) {
            if (item == null || !StringUtils.hasText(item.getRole()) || !StringUtils.hasText(item.getContent())) {
                continue;
            }
            String role = "user".equalsIgnoreCase(item.getRole()) ? "user" : "assistant";
            messages.add(new OpenAiCompatibleClient.ChatMessage(role, item.getContent().trim()));
        }
        return messages;
    }

    private String buildSystemPrompt(
            boolean useWebSearch,
            boolean useManualSearch,
            PersonalizedContextService.PersonalizedContext personalizedContext) {
        StringBuilder prompt = new StringBuilder(BASE_SYSTEM_PROMPT);
        if (useManualSearch) {
            prompt.append(" 当前已关联说明书。对于操作步骤、参数、故障代码、安装、清洗、保养、报错和面板功能等问题，必须优先调用 manual_search 工具获取说明书证据，再作答。");
            prompt.append(" 如果 manual_search 返回的证据不足，请明确说“说明书中未找到足够信息”，然后给出保守建议。");
        }
        if (useWebSearch) {
            prompt.append(" 当问题依赖外部最新信息，或说明书证据不足且确实需要联网补充时，可以调用 web_search 工具。");
            prompt.append(" web_search 的 intent 只能从 operation_guide、troubleshooting、product_info、latest_info、generic_chat 中选择。");
            prompt.append(" 如果搜索结果不足，请明确说“搜索结果不足”。");
        }
        String personalizedPrompt = personalizedContextService.buildPromptBlock(personalizedContext);
        if (StringUtils.hasText(personalizedPrompt)) {
            prompt.append("\n\n").append(personalizedPrompt);
        }
        return prompt.toString();
    }

    private String resolveTarget(String rawTarget) {
        String candidate = trimToNull(rawTarget);
        if (candidate == null) {
            candidate = trimToNull(aiCompatProperties.getDefaultTarget());
        }
        return candidate != null ? candidate.toLowerCase(Locale.ROOT) : "local";
    }

    private String resolveToolModel(AiCompatProperties.TargetConfig targetConfig) {
        if (targetConfig != null && StringUtils.hasText(targetConfig.getToolModel())) {
            return targetConfig.getToolModel().trim();
        }
        return targetConfig != null ? trimToNull(targetConfig.getModel()) : null;
    }

    private String resolveManualLookupId(QARequest req) {
        if (req == null) {
            return null;
        }
        String manualRefId = trimToNull(req.getManualRefId());
        if (manualRefId != null) {
            return manualRefId;
        }
        return trimToNull(req.getManualId());
    }

    private String buildEffectiveQuery(QARequest req, boolean hasImage) {
        String query = trimToNull(req != null ? req.getQuery() : null);
        if (query != null) {
            return query;
        }
        if (hasImage && req != null && Boolean.TRUE.equals(req.getEnableImageRecognition())) {
            return "请识别这张家电图片，并根据识别结果给出简短建议。";
        }
        return null;
    }

    private String buildQueryWithVisionContext(String query, QAResponse.VisionResult vision) {
        if (vision == null) {
            return query;
        }

        StringBuilder builder = new StringBuilder();
        builder.append("用户问题：").append(query);
        builder.append("\n\n图片识别结果：");
        appendVisionLine(builder, "图片类型", vision.getImageType());
        appendVisionLine(builder, "家电类别", vision.getCategoryCode());
        appendVisionLine(builder, "品牌", vision.getBrandName());
        appendVisionLine(builder, "型号", vision.getModelName());
        appendVisionLine(builder, "故障码", vision.getFaultCode());
        appendVisionLine(builder, "识别文本", vision.getRecognizedText());
        if (vision.getConfidence() != null) {
            builder.append("\n- 置信度：").append(String.format(Locale.ROOT, "%.2f", vision.getConfidence()));
        }
        if (StringUtils.hasText(vision.getQueryHint())) {
            builder.append("\n- 推荐检索词：").append(vision.getQueryHint().trim());
        }
        builder.append("\n\n请结合上述识别结果回答；如果识别结果不足或不确定，请明确说明。");
        return builder.toString();
    }

    private void appendVisionLine(StringBuilder builder, String label, String value) {
        if (!StringUtils.hasText(value) || "unknown".equalsIgnoreCase(value.trim())) {
            return;
        }
        builder.append("\n- ").append(label).append('：').append(value.trim());
    }

    private OpenAiCompatibleClient.ToolSpec buildManualSearchTool() {
        Map<String, Object> queryProperty = new LinkedHashMap<>();
        queryProperty.put("type", "string");
        queryProperty.put("description", "A focused search query used to search inside the current manual.");

        Map<String, Object> topKProperty = new LinkedHashMap<>();
        topKProperty.put("type", "integer");
        topKProperty.put("description", "How many manual fragments to retrieve, usually 2 to 5.");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", queryProperty);
        properties.put("top_k", topKProperty);

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        parameters.put("required", List.of("query"));

        return new OpenAiCompatibleClient.ToolSpec(
                "function",
                new OpenAiCompatibleClient.ToolDefinition(
                        MANUAL_SEARCH_TOOL_NAME,
                        "Search relevant fragments inside the current manual and return grounded evidence.",
                        parameters));
    }

    private OpenAiCompatibleClient.ToolSpec buildWebSearchTool() {
        Map<String, Object> intentProperty = new LinkedHashMap<>();
        intentProperty.put("type", "string");
        intentProperty.put("enum", List.of(
                "operation_guide",
                "troubleshooting",
                "product_info",
                "latest_info",
                "generic_chat"));
        intentProperty.put("description", "The user intent category for the appliance question.");

        Map<String, Object> queryProperty = new LinkedHashMap<>();
        queryProperty.put("type", "string");
        queryProperty.put("description", "The focused web search query to run.");

        Map<String, Object> maxResultsProperty = new LinkedHashMap<>();
        maxResultsProperty.put("type", "integer");
        maxResultsProperty.put("description", "How many search results to retrieve, usually 3 to 5.");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", queryProperty);
        properties.put("intent", intentProperty);
        properties.put("max_results", maxResultsProperty);

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        parameters.put("required", List.of("query", "intent"));

        return new OpenAiCompatibleClient.ToolSpec(
                "function",
                new OpenAiCompatibleClient.ToolDefinition(
                        WEB_SEARCH_TOOL_NAME,
                        "Search the web for appliance operation guides, troubleshooting steps, product information, or latest news.",
                        parameters));
    }

    private ToolArguments parseToolArguments(String rawQuery, String argumentsJson) {
        if (!StringUtils.hasText(argumentsJson)) {
            return defaultToolArguments(rawQuery);
        }
        try {
            ToolArguments parsed = objectMapper.readValue(argumentsJson, ToolArguments.class);
            if (!StringUtils.hasText(parsed.getQuery())) {
                parsed.setQuery(rawQuery);
            }
            if (!StringUtils.hasText(parsed.getIntent())) {
                parsed.setIntent("generic_chat");
            }
            if (parsed.getMaxResults() == null) {
                parsed.setMaxResults(5);
            }
            return parsed;
        } catch (Exception ex) {
            return defaultToolArguments(rawQuery);
        }
    }

    private ToolArguments defaultToolArguments(String rawQuery) {
        ToolArguments args = new ToolArguments();
        args.setQuery(rawQuery);
        args.setIntent("generic_chat");
        args.setMaxResults(5);
        return args;
    }

    private ManualToolArguments parseManualToolArguments(String rawQuery, String argumentsJson) {
        if (!StringUtils.hasText(argumentsJson)) {
            return defaultManualToolArguments(rawQuery);
        }
        try {
            ManualToolArguments parsed = objectMapper.readValue(argumentsJson, ManualToolArguments.class);
            if (!StringUtils.hasText(parsed.getQuery())) {
                parsed.setQuery(rawQuery);
            }
            if (parsed.getTopK() == null) {
                parsed.setTopK(aiCompatProperties.getManualRagTopK());
            }
            return parsed;
        } catch (Exception ex) {
            return defaultManualToolArguments(rawQuery);
        }
    }

    private ManualToolArguments defaultManualToolArguments(String rawQuery) {
        ManualToolArguments args = new ManualToolArguments();
        args.setQuery(rawQuery);
        args.setTopK(aiCompatProperties.getManualRagTopK());
        return args;
    }

    private SearxngSearchService.SearchRequest buildSearchRequest(String rawQuery, ToolArguments arguments) {
        String searchQuery = trimToNull(arguments.getQuery());
        if (searchQuery == null) {
            searchQuery = rawQuery;
        }
        SearxngSearchService.SearchIntent intent = SearxngSearchService.SearchIntent.fromValue(arguments.getIntent());
        boolean applianceRelated = containsAny(rawQuery, APPLIANCE_KEYWORDS)
                || containsAny(rawQuery, BRAND_KEYWORDS)
                || containsAny(searchQuery, APPLIANCE_KEYWORDS)
                || containsAny(searchQuery, BRAND_KEYWORDS)
                || intent != SearxngSearchService.SearchIntent.GENERIC_CHAT;
        List<String> priorityKeywords = extractPriorityKeywords(rawQuery + " " + searchQuery);
        Integer requestedMaxResults = arguments.getMaxResults() != null ? Math.max(arguments.getMaxResults(), 1) : 5;

        return new SearxngSearchService.SearchRequest(
                rawQuery,
                searchQuery,
                intent,
                applianceRelated,
                priorityKeywords,
                requestedMaxResults);
    }

    private List<String> extractPriorityKeywords(String query) {
        Set<String> keywords = new LinkedHashSet<>();
        collectMatches(query, APPLIANCE_KEYWORDS, keywords);
        collectMatches(query, BRAND_KEYWORDS, keywords);
        collectMatches(query, OPERATION_KEYWORDS, keywords);
        collectMatches(query, TROUBLESHOOTING_KEYWORDS, keywords);
        collectMatches(query, PRODUCT_KEYWORDS, keywords);

        Matcher matcher = MODEL_TOKEN_PATTERN.matcher(query);
        while (matcher.find() && keywords.size() < 8) {
            keywords.add(matcher.group());
        }
        return new ArrayList<>(keywords);
    }

    private void collectMatches(String query, List<String> candidates, Set<String> output) {
        String normalizedQuery = query.toLowerCase(Locale.ROOT);
        for (String candidate : candidates) {
            if (output.size() >= 8) {
                return;
            }
            if (StringUtils.hasText(candidate) && normalizedQuery.contains(candidate.toLowerCase(Locale.ROOT))) {
                output.add(candidate);
            }
        }
    }

    private boolean containsAny(String text, List<String> keywords) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (StringUtils.hasText(keyword) && lower.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private void mergeManualSources(
            Set<String> sourceFragmentIds,
            Map<String, QAResponse.SourceFragment> sourcesById,
            List<ManualRetrievalService.RetrievedFragment> fragments) {
        if (fragments == null) {
            return;
        }
        for (ManualRetrievalService.RetrievedFragment fragment : fragments) {
            if (fragment == null || !StringUtils.hasText(fragment.getFragmentId())) {
                continue;
            }
            String fragmentId = fragment.getFragmentId().trim();
            sourceFragmentIds.add(fragmentId);
            sourcesById.putIfAbsent(fragmentId, QAResponse.SourceFragment.builder()
                    .kind("manual_fragment")
                    .fragmentId(fragmentId)
                    .page(fragment.getPage())
                    .chunkIndex(fragment.getChunkIndex())
                    .title(fragment.getPage() != null ? "说明书第 " + fragment.getPage() + " 页" : "说明书片段")
                    .snippet(buildSourceSnippet(fragment.getText()))
                    .sourceType(fragment.getSourceType())
                    .build());
        }
    }

    private void mergeWebSources(
            Map<String, QAResponse.SourceFragment> sourcesById,
            List<SearxngSearchService.SearchResult> searchResults) {
        if (searchResults == null) {
            return;
        }
        for (SearxngSearchService.SearchResult item : searchResults) {
            if (item == null) {
                continue;
            }
            String key = buildWebSourceKey(item);
            if (key == null) {
                continue;
            }
            sourcesById.putIfAbsent(key, QAResponse.SourceFragment.builder()
                    .kind("web_result")
                    .fragmentId(null)
                    .page(null)
                    .chunkIndex(null)
                    .title(trimToNull(item.getTitle()))
                    .url(trimToNull(item.getUrl()))
                    .snippet(buildSourceSnippet(item.getSnippet()))
                    .sourceType("web_search")
                    .build());
        }
    }

    private String buildWebSourceKey(SearxngSearchService.SearchResult item) {
        String url = trimToNull(item.getUrl());
        if (url != null) {
            return "web:" + url;
        }
        String title = trimToNull(item.getTitle());
        if (title != null) {
            return "web:title:" + title;
        }
        String snippet = trimToNull(item.getSnippet());
        return snippet != null ? "web:snippet:" + snippet : null;
    }

    private String buildToolResultPayload(
            SearxngSearchService.SearchRequest searchRequest,
            List<SearxngSearchService.SearchResult> searchResults) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("intent", searchRequest.getIntent().name().toLowerCase(Locale.ROOT));
        payload.put("query", searchRequest.getSearchQuery());
        List<Map<String, String>> items = new ArrayList<>();
        for (SearxngSearchService.SearchResult item : searchResults) {
            Map<String, String> result = new LinkedHashMap<>();
            result.put("title", item.getTitle());
            result.put("url", item.getUrl());
            result.put("snippet", item.getSnippet());
            items.add(result);
        }
        payload.put("results", items);
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception ex) {
            return "{\"error\":\"tool_result_serialization_failed\"}";
        }
    }

    private String buildManualSearchResultPayload(
            ManualToolArguments arguments,
            ManualRetrievalService.RetrievalResult retrieval) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("query", arguments.getQuery());
        if (retrieval.getManual() != null) {
            payload.put("manualRefId", retrieval.getManual().getRefId());
            payload.put("manualId", retrieval.getManual().getManualId());
            payload.put("manualType", retrieval.getManual().getManualType());
            payload.put("manualName", retrieval.getManual().getManualName());
            payload.put("brandName", retrieval.getManual().getBrandName());
            payload.put("modelName", retrieval.getManual().getModelName());
            payload.put("categoryCode", retrieval.getManual().getCategoryCode());
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (ManualRetrievalService.RetrievedFragment fragment : retrieval.getFragments()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("fragmentId", fragment.getFragmentId());
            item.put("page", fragment.getPage());
            item.put("chunkIndex", fragment.getChunkIndex());
            item.put("score", fragment.getScore());
            item.put("keywordScore", fragment.getKeywordScore());
            item.put("bm25Score", fragment.getBm25Score());
            item.put("vectorSimilarity", fragment.getVectorSimilarity());
            item.put("text", fragment.getText());
            items.add(item);
        }
        payload.put("results", items);
        payload.put("insufficientContext", retrieval.isInsufficientContext());
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception ex) {
            return "{\"error\":\"tool_result_serialization_failed\"}";
        }
    }

    private String defaultAnswerText(String content) {
        return defaultAnswerText(content, false);
    }

    private String defaultAnswerText(String content, boolean manualToolUsed) {
        if (StringUtils.hasText(content)) {
            return content.trim();
        }
        if (manualToolUsed) {
            return "说明书中未找到足够信息，请补充更具体的型号、现象或页面后再试。";
        }
        return "搜索结果不足，暂时无法给出可靠答案。";
    }

    private QAResponse.StructuredAnswer buildStructuredAnswer(String answerText, boolean insufficientContext) {
        Map<String, String> sections = extractAnswerSections(answerText);
        String summary = normalizeParagraph(firstNonEmpty(
                sections.get("结论"),
                firstParagraph(answerText),
                insufficientContext ? "请补充更具体的设备型号、面板提示或现象，我再继续帮你判断。" : answerText
        ));
        List<String> steps = normalizeListSection(sections.get("操作步骤"));
        List<String> cautions = normalizeListSection(sections.get("注意事项"));

        return QAResponse.StructuredAnswer.builder()
                .summary(summary)
                .steps(steps)
                .cautions(cautions)
                .basisNote("")
                .build();
    }

    private Map<String, String> extractAnswerSections(String answerText) {
        Map<String, StringBuilder> builders = new LinkedHashMap<>();
        builders.put("结论", new StringBuilder());
        builders.put("操作步骤", new StringBuilder());
        builders.put("注意事项", new StringBuilder());

        String currentSection = "结论";
        String normalized = answerText == null ? "" : answerText.replace("\r\n", "\n");
        for (String line : normalized.split("\n")) {
            String trimmedLine = line.trim();
            Matcher matcher = ANSWER_SECTION_PATTERN.matcher(trimmedLine);
            if (matcher.matches()) {
                currentSection = matcher.group(1);
                String inlineContent = trimToNull(matcher.group(2));
                if (inlineContent != null) {
                    builders.get(currentSection).append(inlineContent).append('\n');
                }
                continue;
            }

            if (trimmedLine.isEmpty() && builders.get(currentSection).length() == 0) {
                continue;
            }
            builders.get(currentSection).append(line).append('\n');
        }

        Map<String, String> sections = new LinkedHashMap<>();
        builders.forEach((key, value) -> sections.put(key, trimToNull(value.toString())));
        return sections;
    }

    private List<String> normalizeListSection(String sectionText) {
        String normalized = trimToNull(sectionText);
        if (normalized == null) {
            return List.of();
        }

        List<String> items = new ArrayList<>();
        for (String line : normalized.replace("\r\n", "\n").split("\n")) {
            String item = trimToNull(line);
            if (item == null) {
                continue;
            }
            item = item.replaceFirst("^[\\-•·*]+\\s*", "");
            item = item.replaceFirst("^\\d+[\\.、]\\s*", "");
            item = trimToNull(item);
            if (item == null || isEmptySectionValue(item)) {
                continue;
            }
            items.add(item);
        }

        if (!items.isEmpty()) {
            return items;
        }

        normalized = normalized.replaceAll("\\s+", " ").trim();
        if (isEmptySectionValue(normalized)) {
            return List.of();
        }
        return List.of(normalized);
    }

    private boolean isEmptySectionValue(String value) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            return true;
        }
        return "无".equals(normalized)
                || "暂无".equals(normalized)
                || "没有".equals(normalized)
                || "无需操作".equals(normalized)
                || "无特殊注意事项".equals(normalized);
    }

    private String normalizeParagraph(String value) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            return "";
        }
        normalized = normalized.replaceFirst("^[【\\[]?(结论|操作步骤|注意事项)[】\\]]?[：:]?\\s*", "");
        return normalized.replaceAll("\\s+", " ").trim();
    }

    private String firstParagraph(String value) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            return null;
        }

        String[] paragraphs = normalized.replace("\r\n", "\n").split("\n\n");
        for (String paragraph : paragraphs) {
            String candidate = normalizeParagraph(paragraph);
            if (candidate != null && !candidate.isEmpty()) {
                return candidate;
            }
        }
        return normalizeParagraph(normalized);
    }

    private String firstNonEmpty(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            String normalized = trimToNull(value);
            if (normalized != null) {
                return normalized;
            }
        }
        return null;
    }

    private String buildSourceSnippet(String text) {
        String normalized = trimToNull(text);
        if (normalized == null) {
            return "";
        }
        normalized = normalized.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 140) {
            return normalized;
        }
        return normalized.substring(0, 140) + " ...[已截断]";
    }

    private QAResponse createBaseResponse() {
        QAResponse response = new QAResponse();
        response.setStatus("error");
        response.setUsedWebSearch(Boolean.FALSE);
        response.setInsufficientContext(Boolean.FALSE);
        response.setSourceFragmentIds(List.of());
        response.setSources(List.of());
        return response;
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    @Data
    private static class ToolArguments {
        private String query;
        private String intent;
        @JsonProperty("max_results")
        private Integer maxResults;
    }

    @Data
    private static class ManualToolArguments {
        private String query;
        @JsonProperty("top_k")
        private Integer topK;
    }
}
