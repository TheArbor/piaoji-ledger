package com.demo.ledger.net;

import android.graphics.BitmapFactory;
import android.util.Base64;
import android.util.Log;

import com.demo.ledger.data.Bill;
import com.demo.ledger.data.Prefs;
import com.demo.ledger.util.ImageTiles;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.Buffer;

/**
 * OpenAI 兼容协议客户端：识别账单与生成月度洞察。
 */
public final class LlmClient {

    private static final String TAG = "Ledger";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private static final String SYSTEM_VISION =
            "你是账单结构化助手。用户会发一张小票、收据或支付截图。\n"
                    + "只输出一个 JSON 对象，不要输出解释、Markdown 或代码围栏。\n"
                    + "字段定义：\n"
                    + "is_bill: 布尔，图片是否是账单或支付记录；\n"
                    + "merchant: 字符串，商家或收款方名称；\n"
                    + "amount: 数字，实付金额，单位元，保留两位小数；\n"
                    + "occurred_at: 字符串，交易时间，格式 yyyy-MM-dd HH:mm，识别不到就给空字符串；\n"
                    + "category: 字符串，只能取以下之一：餐饮外卖、交通、购物、日用、娱乐、医疗、居住、其他；\n"
                    + "confidence: 数字，0 到 1 之间的置信度。\n"
                    + "如果图片不是账单，is_bill 为 false，其余字段留空。";

    private static final String USER_VISION = "请识别这张账单图片，严格按约定的 JSON 结构输出。";

    private static final String SYSTEM_VISION_LIST =
            "你是账单结构化助手。用户会发一张支付账单截图（可能是一整张长图，也可能是长图的一段）。"
                    + "图里可能有几十条交易，请从上到下逐条提取，不要遗漏任何一条支出记录。\n"
                    + "只输出一个 JSON 对象，不要解释、Markdown 或代码围栏，格式：\n"
                    + "{\"bills\":[{\"merchant\":\"商家或收款方\",\"amount\":12.34,"
                    + "\"occurred_at\":\"yyyy-MM-dd HH:mm\",\"category\":\"餐饮外卖\"}]}\n"
                    + "规则：amount 是实付金额，单位元，必须为正数；分组标题或页面顶部出现的日期用于推断每条记录的日期，"
                    + "实在推断不出就给空字符串。只有支出才输出，忽略充值、提现、退款、红包收入等金额前带 + 的记录。\n"
                    + "category 只能取：餐饮外卖、交通、购物、日用、娱乐、医疗、居住、其他。\n"
                    + "没有支出时输出 {\"bills\":[]}，不要编造记录。";

    private static final String USER_VISION_LIST =
            "请提取整张图片里的全部支出记录，逐条输出到 bills 数组，不要遗漏。";

    private static final String SYSTEM_INSIGHT =
            "你是个人消费分析助手。根据用户给出的月度消费统计，输出一个 JSON 对象："
                    + "{\"summary\":\"一句话总结\",\"tips\":[\"建议1\",\"建议2\",\"建议3\"]}。\n"
                    + "只输出 JSON，不要 Markdown 围栏，不要解释。"
                    + "建议要具体、口语化，必须引用真实的分类和金额，指出占比最高的分类，并给出可执行的省钱做法。";

    private static final String SYSTEM_REPORT =
            "你是严谨的个人财务分析师。根据用户给出的消费数据（可能是日/周/月/年任意周期），输出一个 JSON 对象：\n"
                    + "{\"score\":0到100的整数,\"verdict\":\"一句话结论\","
                    + "\"overview\":\"总体分析，2-3句\",\"structure\":\"支出结构分析，2-3句\","
                    + "\"risk\":\"风险与异常提示，1-2句\",\"advice\":[\"建议1\",\"建议2\",\"建议3\"]}\n"
                    + "只输出 JSON，不要 Markdown 围栏，不要解释。"
                    + "先判断统计周期是日/周/月/年，分析粒度和建议要贴合该周期：日看单笔与场景，周看节奏，月看结构，年看趋势与季节性。"
                    + "必须引用真实的分类、金额、环比与峰值日期，不要编造数据；"
                    + "score 表示这个周期消费结构的健康度，花得越集中、异常越多分数越低。";

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .callTimeout(150, TimeUnit.SECONDS)
            .build();

    /**
     * 逐张识别进度：整图识别不再切段，done=0 表示已开始，done=1 表示完成。
     */
    public interface TileListener {
        void onTile(int done, int total, int billsSoFar);
    }

    /** 原图可直接上传的体积/像素上限：控制在接口单图限制内，超了才压缩一次。 */
    private static final long MAX_INLINE_BYTES = 6_000_000L;
    private static final long MAX_INLINE_PIXELS = 24_000_000L;
    private static final AtomicInteger UPLOAD_SEQ = new AtomicInteger();

    /**
     * 整图单次识别上限：实测 700x7880（5.5MP、约 50 笔）整图请求会超过 90s 读超时，
     * 因此只把普通手机截图（最多约两屏）走整图，更长的账单截图改用并行分段。
     */
    private static final long WHOLE_IMAGE_MAX_PIXELS = 6_000_000L;
    private static final int WHOLE_IMAGE_MAX_HEIGHT = 4000;
    /** 超长截图并行分段数：3 路并发兼顾速度与供应商限流。 */
    private static final int TILE_WORKERS = 3;

    public static final class Recognized {
        public Bill bill;
        public String raw;
    }

    private LlmClient() {
    }

    public static Recognized recognize(File image, Prefs prefs) throws IOException {
        String content = chat(prefs, buildVisionBody(prefs, image));
        Recognized recognized = new Recognized();
        recognized.raw = content;
        try {
            recognized.bill = LlmParser.parseBill(content);
        } catch (RuntimeException e) {
            throw new IOException(e.getMessage()
                    + "（原始返回：" + LlmParser.truncate(content, 200) + "）", e);
        }
        return recognized;
    }

    /**
     * 批量识别：普通截图整图一次请求，超长截图并行分段后合并去重。
     */
    public static List<Bill> recognizeMany(Prefs prefs, File image, File tileDir) throws IOException {
        return recognizeMany(prefs, image, tileDir, null);
    }

    /**
     * 识别批量账单：
     * 普通图片直接整图一次请求；超长截图（单图会让供应商处理过慢）自动并行分段，
     * 分段时间从串行 8 次降到约 3 轮并发。
     */
    public static List<Bill> recognizeMany(Prefs prefs, File image, File tileDir,
                                           TileListener listener) throws IOException {
        long start = System.currentTimeMillis();
        int[] size = imageSize(image);
        long pixels = size[0] > 0 ? (long) size[0] * size[1] : -1;
        boolean whole = pixels > 0 && pixels <= WHOLE_IMAGE_MAX_PIXELS
                && size[1] <= WHOLE_IMAGE_MAX_HEIGHT;

        List<Bill> result;
        if (whole) {
            Log.d(TAG, "整图识别：" + size[0] + "x" + size[1]);
            if (listener != null) {
                listener.onTile(0, 1, 0);
            }
            result = requestBills(prefs, buildVisionBody(prefs, image,
                    SYSTEM_VISION_LIST, USER_VISION_LIST, 3200, 4000));
            if (listener != null) {
                listener.onTile(1, 1, result.size());
            }
        } else {
            Log.d(TAG, "超长截图并行分段：" + size[0] + "x" + size[1]);
            result = recognizeTiles(prefs, image, tileDir, listener);
        }
        Log.d(TAG, "识别完成：" + result.size() + " 笔，耗时 "
                + (System.currentTimeMillis() - start) + "ms");
        return dedupe(result);
    }

    private static List<Bill> requestBills(Prefs prefs, RequestBody body) throws IOException {
        String content = chat(prefs, body);
        try {
            return LlmParser.parseBills(content);
        } catch (RuntimeException e) {
            throw new IOException(e.getMessage()
                    + "（原始返回：" + LlmParser.truncate(content, 200) + "）", e);
        }
    }

    /**
     * 超长截图分段识别：3 路并发送出，减少串行等待；结果合并后统一去重。
     */
    private static List<Bill> recognizeTiles(Prefs prefs, File image, File tileDir,
                                             TileListener listener) throws IOException {
        List<File> tiles = ImageTiles.split(image, tileDir);
        int total = tiles.size();
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(TILE_WORKERS, total));
        CompletionService<List<Bill>> completion = new ExecutorCompletionService<>(pool);
        for (File tile : tiles) {
            completion.submit(() -> requestBills(prefs, buildVisionBody(prefs, tile,
                    SYSTEM_VISION_LIST, USER_VISION_LIST, 1600, 3000)));
        }

        List<Bill> result = new ArrayList<>();
        IOException lastError = null;
        int success = 0;
        try {
            for (int i = 0; i < total; i++) {
                try {
                    result.addAll(completion.take().get());
                    success++;
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    lastError = cause instanceof IOException
                            ? (IOException) cause
                            : new IOException(String.valueOf(cause == null ? "识别失败" : cause.getMessage()), cause);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("识别被中断", e);
                }
                if (listener != null) {
                    listener.onTile(i + 1, total, result.size());
                }
            }
        } finally {
            pool.shutdownNow();
            for (File tile : tiles) {
                if (!tile.equals(image) && !tile.delete()) {
                    Log.w(TAG, "切片临时图删除失败：" + tile.getAbsolutePath());
                }
            }
        }
        if (success == 0 && lastError != null) {
            throw lastError;
        }
        return result;
    }

    private static List<Bill> dedupe(List<Bill> bills) {
        List<Bill> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Bill bill : bills) {
            Calendar calendar = Calendar.getInstance();
            calendar.setTimeInMillis(bill.occurredAt);
            String key = calendar.get(Calendar.YEAR) + "-" + calendar.get(Calendar.DAY_OF_YEAR)
                    + "|" + bill.amountCents + "|" + bill.merchant;
            if (seen.add(key)) {
                result.add(bill);
            }
        }
        return result;
    }

    public static LlmParser.Insight insight(Prefs prefs, String facts) throws IOException {
        String content = chat(prefs, buildTextBody(prefs, SYSTEM_INSIGHT, facts));
        try {
            return LlmParser.parseInsight(content);
        } catch (RuntimeException e) {
            throw new IOException(e.getMessage()
                    + "（原始返回：" + LlmParser.truncate(content, 200) + "）", e);
        }
    }

    public static LlmParser.Report report(Prefs prefs, String facts) throws IOException {
        String content = chat(prefs, buildTextBody(prefs, SYSTEM_REPORT, facts));
        try {
            return LlmParser.parseReport(content);
        } catch (RuntimeException e) {
            throw new IOException(e.getMessage()
                    + "（原始返回：" + LlmParser.truncate(content, 200) + "）", e);
        }
    }

    /**
     * 测试连接：调用 /models，避免直接烧 token。
     */
    public static String testConnection(Prefs prefs) throws IOException {
        requireConfig(prefs);
        String url = endpoint(prefs.getBaseUrl(), "models");
        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + prefs.getApiKey())
                .get()
                .build();
        Log.d(TAG, "GET " + url);
        try (Response response = CLIENT.newCall(request).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new IOException(friendlyError(response.code(), body, prefs.getModel()));
            }
            return "连接成功，返回 " + LlmParser.truncate(body, 120);
        }
    }

    private static String friendlyError(int code, String body, String model) {
        if (code == 401 || code == 403) {
            return "鉴权失败（" + code + "）：API Key 无效，或与当前 BaseURL 不属于同一家供应商";
        }
        if (code == 404 && (body.contains("model_not_found") || body.contains("Model not exist"))) {
            return "模型不存在（404）：当前模型 " + model
                    + " 在该账号不可用，请检查模型名，或在供应商控制台开通该模型";
        }
        if (code == 429) {
            return "请求过于频繁或额度不足（429），稍后再试或检查账户余额";
        }
        return "HTTP " + code + "：" + LlmParser.truncate(body, 400);
    }

    /**
     * 把识别过程中的异常（含超时和内存不足）转成用户能看懂的提示。
     */
    public static String friendlyThrowable(Throwable t) {
        if (t instanceof OutOfMemoryError) {
            return "图片过大，内存不足，请改用更小的截图或分段导入";
        }
        if (t instanceof java.io.InterruptedIOException) {
            return "请求超时，请检查网络后重试";
        }
        String message = t == null ? null : t.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return t == null ? "未知错误" : t.getClass().getSimpleName();
        }
        return message;
    }

    private static RequestBody buildVisionBody(Prefs prefs, File image) throws IOException {
        return buildVisionBody(prefs, image, SYSTEM_VISION, USER_VISION, 1600, 1024);
    }

    /**
     * 图片体积可控且本身就是 JPEG 时直接上传原图，避免多一次解码压缩；
     * 其余情况（PNG/WEBP、超大图）按长边压缩一次，保证接口兼容。
     */
    private static RequestBody buildVisionBody(Prefs prefs, File image, String system,
                                               String user, int maxSide, int maxTokens)
            throws IOException {
        long pixels = imagePixels(image);
        boolean inline = isJpeg(image) && !hasExifRotation(image)
                && image.length() <= MAX_INLINE_BYTES
                && pixels > 0 && pixels <= MAX_INLINE_PIXELS;

        File upload = image;
        if (!inline) {
            upload = new File(image.getParentFile(), "upload_" + System.currentTimeMillis()
                    + "_" + UPLOAD_SEQ.incrementAndGet() + ".jpg");
            com.demo.ledger.util.ImageCompressor.compressToJpeg(image, upload, maxSide, 88);
            Log.d(TAG, "图片需预处理，压缩后上传：" + upload.length() + " 字节");
        } else {
            Log.d(TAG, "整图直接上传：" + image.length() + " 字节，像素 " + pixels);
        }

        Buffer buffer = new Buffer();
        String prefix = "{\"model\":" + JSONObject.quote(prefs.getModel())
                + ",\"temperature\":0,\"max_tokens\":" + maxTokens + ",\"messages\":["
                + "{\"role\":\"system\",\"content\":" + JSONObject.quote(system) + "},"
                + "{\"role\":\"user\",\"content\":["
                + "{\"type\":\"text\",\"text\":" + JSONObject.quote(user) + "},"
                + "{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/jpeg;base64,";
        buffer.writeUtf8(prefix);
        buffer.writeUtf8(Base64.encodeToString(readAll(upload), Base64.NO_WRAP));
        buffer.writeUtf8("\"}}]}]}");

        if (!upload.equals(image) && !upload.delete()) {
            Log.w(TAG, "临时上传图删除失败：" + upload.getAbsolutePath());
        }

        byte[] body = buffer.readByteArray();
        Log.d(TAG, "上传请求体大小 " + body.length + " 字节");
        return RequestBody.create(body, JSON);
    }

    private static long imagePixels(File file) {
        int[] size = imageSize(file);
        return size[0] > 0 ? (long) size[0] * size[1] : -1;
    }

    /** 只读图片尺寸，不分配像素内存；读取失败返回 {0,0}。 */
    private static int[] imageSize(File file) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                return new int[]{bounds.outWidth, bounds.outHeight};
            }
        } catch (Throwable ignored) {
            // 解码失败按非 JPEG 处理，走压缩分支并给出明确错误
        }
        return new int[]{0, 0};
    }

    /** 只有真正的 JPEG 才能直接贴 JPEG data URL，避免把 PNG/WEBP 字节标错类型。 */
    private static boolean isJpeg(File file) {
        try (FileInputStream in = new FileInputStream(file)) {
            return in.read() == 0xFF && in.read() == 0xD8;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 相机照片常带 EXIF 旋转，直接原图上传可能让模型看到横躺的图，
     * 这类图片走压缩分支，由 ImageCompressor 统一纠正方向。
     */
    @SuppressWarnings("deprecation")
    private static boolean hasExifRotation(File file) {
        try {
            android.media.ExifInterface exif =
                    new android.media.ExifInterface(file.getAbsolutePath());
            int orientation = exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION,
                    android.media.ExifInterface.ORIENTATION_NORMAL);
            return orientation != android.media.ExifInterface.ORIENTATION_NORMAL
                    && orientation != android.media.ExifInterface.ORIENTATION_UNDEFINED;
        } catch (Throwable e) {
            // 读不到 EXIF 就当作需要预处理，宁慢一点也别让方向出错
            return true;
        }
    }

    private static RequestBody buildTextBody(Prefs prefs, String system, String user) throws IOException {
        String body = "{\"model\":" + JSONObject.quote(prefs.getModel())
                + ",\"temperature\":0.3,\"max_tokens\":800,\"messages\":["
                + "{\"role\":\"system\",\"content\":" + JSONObject.quote(system) + "},"
                + "{\"role\":\"user\",\"content\":" + JSONObject.quote(user) + "}]}";
        return RequestBody.create(body.getBytes("UTF-8"), JSON);
    }

    private static String chat(Prefs prefs, RequestBody body) throws IOException {
        requireConfig(prefs);
        String url = endpoint(prefs.getBaseUrl(), "chat/completions");
        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + prefs.getApiKey())
                .post(body)
                .build();
        Log.d(TAG, "POST " + url + " model=" + prefs.getModel());

        String text;
        int code;
        try (Response response = CLIENT.newCall(request).execute()) {
            code = response.code();
            text = response.body() == null ? "" : response.body().string();
        }
        if (code < 200 || code >= 300) {
            throw new IOException(friendlyError(code, text, prefs.getModel()));
        }
        try {
            JSONObject root = new JSONObject(text);
            JSONArray choices = root.optJSONArray("choices");
            if (choices == null || choices.length() == 0) {
                throw new IOException("返回里没有 choices：" + LlmParser.truncate(text, 400));
            }
            JSONObject message = choices.getJSONObject(0).optJSONObject("message");
            String content = message == null ? null : message.optString("content", "");
            if (content == null || content.trim().isEmpty()) {
                throw new IOException("返回内容为空：" + LlmParser.truncate(text, 400));
            }
            return content;
        } catch (JSONException e) {
            throw new IOException("返回不是合法 JSON：" + LlmParser.truncate(text, 400), e);
        }
    }

    private static void requireConfig(Prefs prefs) throws IOException {
        if (prefs.getBaseUrl().isEmpty() || prefs.getApiKey().isEmpty() || prefs.getModel().isEmpty()) {
            throw new IOException("请先在设置页填写 BaseURL、API Key 和模型名");
        }
        if (!prefs.getBaseUrl().startsWith("http://") && !prefs.getBaseUrl().startsWith("https://")) {
            throw new IOException("BaseURL 必须以 http:// 或 https:// 开头");
        }
    }

    private static String endpoint(String baseUrl, String path) {
        String base = baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.endsWith("/" + path)) {
            return base;
        }
        return base + "/" + path;
    }

    private static byte[] readAll(File file) throws IOException {
        FileInputStream in = new FileInputStream(file);
        try {
            byte[] data = new byte[(int) file.length()];
            int offset = 0;
            while (offset < data.length) {
                int read = in.read(data, offset, data.length - offset);
                if (read < 0) {
                    break;
                }
                offset += read;
            }
            return data;
        } finally {
            in.close();
        }
    }

    public static String maskKey(String key) {
        if (key == null || key.length() < 8) {
            return "****";
        }
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }
}
