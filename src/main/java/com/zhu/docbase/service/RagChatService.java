package com.zhu.docbase.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
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

    /**
     * @param topK 检索片段数。
     *             默认 5，不是随便定的：全库 4,497 个 chunk 实测平均 532 token、最大 604
     *             （CL100K 真实分词，不是按字符数估算），topK=5 典型占 2,658 token，
     *             加上问题/指令/回答预留后正好吃满 4096 上下文。
     *             再往上加必须同步提高 num_ctx（见 application.yaml 的 num-ctx 注释），
     *             否则超出部分被 Ollama 静默截断，检索得再多也装不进去，回答反而更差。
     * @param similarityThreshold 相似度阈值，0 = 不过滤。
     *             默认关闭：不同 embedding 的分数分布不同，bge-m3 + COSINE 集中在 0.5~0.8，
     *             硬套一个通用阈值容易把相关内容一起滤掉。要开先量分布。
     *
     * <p>注意：这两个值必须用构造器参数注入，不能用 {@code @Value} 字段。
     * 字段注入发生在构造器执行之后，构造器里读到的还是 0 ——
     * 那样会得到 {@code topK(0)}，运行时报
     * "The number of documents to be returned must be greater than zero"。
     */
    public RagChatService(ChatClient.Builder builder,
                          VectorStore vectorStore,
                          @Value("${rag.chat.top-k:5}") int topK,
                          @Value("${rag.chat.similarity-threshold:0}") double similarityThreshold) {
        // 构建一个"干净"的 ChatClient，不绑定任何默认 Advisor
        // 这样 ask() 和 chat() 才能通过动态添加 Advisor 来区分行为。
        this.chatClient = builder.build();

        // ========== 构建 RAG Advisor ==========
        // QuestionAnswerAdvisor 是一个拦截器，它会在请求到达 LLM 之前：
        //   1. 拦截用户问题
        //   2. 调用 vectorStore.similaritySearch() 检索最相关的文档片段
        //   3. 把检索结果拼接到 Prompt 中，形成"基于以下资料回答问题：..."
        //   4. 把增强后的 Prompt 交给 LLM
        SearchRequest.Builder search = SearchRequest.builder().topK(topK);
        if (similarityThreshold > 0) {
            search.similarityThreshold(similarityThreshold);
        }
        this.ragAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(search.build())
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
