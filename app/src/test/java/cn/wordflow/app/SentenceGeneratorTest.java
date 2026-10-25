package cn.wordflow.app;
import org.junit.Test;
import static org.junit.Assert.*;

public class SentenceGeneratorTest {
    @Test public void usesOnlyOfficialProviderEndpoints() {
        assertEquals("https://api.deepseek.com/chat/completions", SentenceGenerator.endpoint("DeepSeek"));
        assertEquals("https://openrouter.ai/api/v1/chat/completions", SentenceGenerator.endpoint("OpenRouter"));
        try { SentenceGenerator.endpoint("other"); fail(); } catch (IllegalArgumentException expected) { }
    }
    @Test public void requestsSingleExampleAndUsesSelectedModelWithoutKeysInPayload() throws Exception {
        var body = SentenceGenerator.request("curious", "OpenRouter", "openrouter/free");
        assertEquals("openrouter/free", body.getString("model")); assertFalse(body.getBoolean("stream"));
        assertTrue(body.getJSONArray("messages").getJSONObject(1).getString("content").contains("curious"));
        assertEquals("deepseek-flash", SentenceGenerator.request("hello", "DeepSeek", "ignored").getString("model"));
        assertEquals("disabled", SentenceGenerator.request("hello", "DeepSeek", "ignored").getJSONObject("thinking").getString("type"));
        assertFalse(body.has("api_key"));
    }
    @Test public void extractsOnlyCompletedTextAndRejectsMissingOrTruncatedResponses() throws Exception {
        assertEquals("I am curious about the world.", SentenceGenerator.parse("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\" I am curious about the world. \"}}]}"));
        for (String invalid : new String[]{"{}", "{\"choices\":[]}", "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"unfinished\"}}]}", "{\"choices\":[{\"message\":{\"content\":null}}]}"}) {
            try { SentenceGenerator.parse(invalid); fail(); } catch (Exception expected) { }
        }
    }
}
