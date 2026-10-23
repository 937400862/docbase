package com.zhu.docbase.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

/**
 * RAG 问答服务：封装两种对话模式。
 *  - ask()  ：走 RAG 流程（混合检索 + 生成答案）
 *  - chat() ：纯 LLM 对话（不检索知识库）
 *
 * <h3>2026-10-23 的重要改动：去掉 QuestionAnswerAdvisor</h3>
 * 原来用 Spring AI 的 {@code QuestionAnswerAdvisor} 把「检索 + 拼上下文」焊在一起，
 * 插不进混合检索这一层。现在改成手写三段：
 * <pre>
 *   HybridRetriever.retrieve()  向量 top-20 + BM25 top-20 → RRF → top-5
 *        ↓
 *   填 prompts/qa.txt 模板
 *        ↓
 *   chatClient 生成
 * </pre>
 *
 * <p>代价是上下文拼装要自己管（用空行连接），收益是候选数、去重、排序规则都能自己控制。
 * 实测混合检索让 30 题评测集的 top-5 命中从 20/30 提到 26/30。
 *
 * @author ：zhuzj
 * @date ：2026/10/16 13:13
 */
@Slf4j
@Service
public class RagChatService {

    private final ChatClient chatClient;
    private final HybridRetriever retriever;
    private final String promptTemplate;

    /**
     * @param promptFile 问答提示词模板的 classpath 位置。
     *                   模板里有两个占位符：{@code {query}} 和 {@code {question_answer_context}}。
     *
     *                   <p>为什么必须外置：Spring AI 的默认模板最后一句是
     *                   "If the answer is not in the context, inform the user that you can't answer the question"，
     *                   3B 会严格照做 —— 只要没有一个片段完整覆盖问题就整句拒答。
     *                   实测泛问题目 gold 就在 top-5 里，模型仍回答"上下文中没有提供相关信息"。
     *                   换成现在的四条要求后，同一道题变成能列出 10 个相关接口并综合。
     */
    public RagChatService(ChatClient.Builder builder,
                          HybridRetriever retriever,
                          @Value("${rag.chat.prompt-file:prompts/qa.txt}") String promptFile) {
        // 构建一个"干净"的 ChatClient，不绑定任何默认 Advisor
        // 这样 ask() 和 chat() 才能区分行为（ask 自己拼 prompt，chat 直接发）
        this.chatClient = builder.build();
        this.retriever = retriever;
        // 读不到直接启动失败，不要静默退回默认模板 —— 那样改了等于没改
        this.promptTemplate = readPrompt(promptFile);
    }

    private static String readPrompt(String location) {
        try {
            return new ClassPathResource(location).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取提示词模板失败: " + location, e);
        }
    }

    /**
     * 基于知识库的问答（RAG 模式）。
     * 流程：用户提问 → 混合检索（向量 + BM25 → RRF）→ 填模板 → LLM 生成
     *
     * @param question 用户问题
     * @return LLM 基于检索到的文档片段生成的答案
     */
    public String ask(String question) {
        List<Document> docs = retriever.retrieve(question);
        // 用空行分隔各片段：与原来 QuestionAnswerAdvisor 的拼装方式一致，保持提示词形态不变
        String context = docs.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n\n"));

        // 直接字符串替换，不用 PromptTemplate：模板里只有两个占位符，
        // 替换更可控，也避免正文里出现花括号时被模板引擎误解析
        String prompt = promptTemplate
                .replace("{query}", question)
                .replace("{question_answer_context}", context);

        log.debug("RAG 检索到 {} 个片段，上下文 {} 字符", docs.size(), context.length());
        return chatClient.prompt()
                .user(prompt)
                .call()
                .content();
    }

    /**
     * 纯对话模式（不检索知识库）。
     * 适用于闲聊、通用知识问答等不需要个人知识库的场景。
     *
     * @param message 用户消息
     * @return LLM 的回答
     */
    public String chat(String message) {
        return chatClient.prompt()
                .user(message)
                .call()
                .content();
    }
}
