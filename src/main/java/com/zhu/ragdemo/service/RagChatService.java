package com.zhu.ragdemo.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

/**
 * RAG 问答服务：封装两种对话模式。
 *  - ask()  ：走 RAG 流程（检索知识库 + 生成答案）
 *  - chat() ：纯 LLM 对话（不检索知识库）
 * @author ：zhuzj
 * @date ：2026/10/16 13:13
 */
@Service
public class RagChatService {

    private final ChatClient chatClient;
    private final QuestionAnswerAdvisor ragAdvisor;

    public RagChatService(ChatClient.Builder builder, VectorStore vectorStore) {
        // 构建一个"干净"的 ChatClient，不绑定任何默认 Advisor
        // 这样 ask() 和 chat() 才能通过动态添加 Advisor 来区分行为。
        this.chatClient = builder.build();
        // ========== 构建 RAG Advisor ==========
        // QuestionAnswerAdvisor 是一个拦截器，它会在请求到达 LLM 之前：
        //   1. 拦截用户问题
        //   2. 调用 vectorStore.similaritySearch() 检索最相关的文档片段
        //   3. 把检索结果拼接到 Prompt 中，形成"基于以下资料回答问题：..."
        //   4. 把增强后的 Prompt 交给 LLM
        this.ragAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(
                        SearchRequest.builder()
                                // topK(5)：检索时返回最相似的 5 个文档片段。
                                //   - 调大：召回率提高，但 Prompt 变长，可能超出上下文窗口或引入噪声
                                //   - 调小：精准度提高，但可能漏掉关键信息
                                //   - 建议范围：3-8，根据你的文档密度调整
                                .topK(5)
                                // similarityThreshold(0.7)：相似度阈值（0-1）。
                                //   - 只有相似度高于该值的片段才会被返回。
                                //   - 调高（如 0.8）：只保留高度相关的片段，减少噪声
                                //   - 调低（如 0.5）：召回更多片段，但可能引入无关内容
                                //   - 注意：不同 Embedding 模型的分数分布不同，需要实测调整
                                //   - 如果检索结果为空，可以适当降低这个阈值
//                                .similarityThreshold(0.7)
                                .build())
                .build();
    }

    /**
     * 基于知识库的问答（RAG 模式）。
     * 流程：用户提问 → Advisor 拦截 → 向量检索 → 检索结果注入 Prompt → LLM 生成答案
     *
     * @param question 用户问题，例如"项目A使用了哪些技术栈？"
     * @return LLM 基于检索到的文档片段生成的答案
     */
    public String ask(String question) {
        return chatClient.prompt()
                // 只在这一条请求中启用 RAG Advisor，其他请求不受影响
                .advisors(ragAdvisor)
                .user(question)
                .call()
                .content();
    }

    /**
     * 纯对话模式（不检索知识库）。
     * 流程：用户提问 → 直接发给 LLM → 生成答案
     * 适用于闲聊、通用知识问答等不需要个人知识库的场景。
     *
     * @param message 用户消息
     * @return LLM 的回答
     */
    public String chat(String message) {
        return chatClient.prompt()
                // 注意：这里没有 .advisors(ragAdvisor)，所以不会触发检索
                .user(message)
                .call()
                .content();
    }

}
