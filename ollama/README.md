# ollama/

两个 Modelfile，对应 `application-local.yaml` 里用到的自定义模型。

## 需要吗？

**不需要也能跑。** `application.yaml` 默认用官方标签，`ollama pull` 完就可以启动：

```bash
ollama pull bge-m3
ollama pull qwen2.5:3b
```

只有在**显存不足 4 GB**、需要压低 `num_gpu` 的时候，才用这里的 Modelfile 生成调优过的变体。
默认参数是直接在官方模型上跑，显存小的机器会 OOM 或者速度骤降。

## 怎么用

```bash
# 问答模型（qwen2.5-3b-lowvram）
ollama pull qwen2.5:3b
ollama create qwen2.5-3b-lowvram -f ollama/Modelfile-qwen2.5-3b-lowvram

# 脱敏模型（llama3.2-3b-sanitize）—— 只在开启语义脱敏时需要
ollama pull llama3.2:3b
ollama create llama3.2-3b-sanitize -f ollama/Modelfile-llama3.2-3b-sanitize
```

创建完在 `application-local.yaml` 里指向它们：

```yaml
spring:
  ai:
    ollama:
      chat:
        model: qwen2.5-3b-lowvram
rag:
  sanitize:
    model: llama3.2-3b-sanitize
```

## 显存不是 3 GB 怎么办

**`num_gpu` 是唯一有效的开关。** 实测在 Ollama 0.12.x 上，`low_vram` 开或关对显存占用和
生成速度没有任何影响——两个 Modelfile 里保留它只是为了兼容旧版本。

调法：先用一个明显偏小的值确保能加载，然后每次 +2，观察 `ollama ps` 的显存占用和实际 tok/s，
直到显存余量降到 200 MB 左右停下。

两个文件里的表格都是实测数据，可以直接对照自己的显存挑一档。

## 为什么问答和脱敏用两个不同的模型

不是因为分工好看，是因为 **qwen 系列做中文姓名脱敏会失效**：

| 模型 | 输入"王建国和李秀兰上周去了北京出差" | 结果 |
|---|---|---|
| qwen2.5-3b | `[PERSON]建国和[PERSON]秀兰上周去了[ADDRESS]出差` | ❌ 只替换姓，名还露着 |
| llama3.2-3b | `[PERSON]和[PERSON]上周去了[ADDRESS]出差` | ✅ 整体替换 |

qwen 快 60%（30 vs 19 tok/s），所以问答用它；脱敏对准确性要求高于速度，所以换 llama。

顺带一提：`qwen2.5:7b` 也评估过，但同属 qwen2.5 家族、共用分词器，这个问题大概率继承，
加上 4.7 GB 权重在 3 GB 显存上要分 10 层加载，速度不划算，最终没有采用。
