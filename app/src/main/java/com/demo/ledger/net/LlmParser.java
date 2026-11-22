package com.demo.ledger.net;

import com.demo.ledger.data.Bill;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 模型不一定严格守格式，这里做宽容解析；解析不出来就报错，不猜值。
 */
public final class LlmParser {

    public static final class Insight {
        public String summary = "";
        public final List<String> tips = new ArrayList<>();
    }

    public static final class Report {
        public int score;
        public String verdict = "";
        public String overview = "";
        public String structure = "";
        public String risk = "";
        public final List<String> advice = new ArrayList<>();
    }

    private LlmParser() {
    }

    public static Bill parseBill(String content) {
        try {
            JSONObject object = new JSONObject(extractJson(content));
            if (object.has("is_bill") && !object.optBoolean("is_bill", true)) {
                throw new IllegalStateException("这张图里没有识别出账单或支付记录");
            }
            Bill bill = readBill(object);
            if (bill == null) {
                throw new IllegalStateException("没识别到有效金额，请手动填写");
            }
            return bill;
        } catch (JSONException e) {
            throw new IllegalStateException("解析模型返回失败：" + e.getMessage(), e);
        }
    }

    /**
     * 一张账单列表截图里可能有多条支出，也容忍模型直接返回单个对象。
     */
    public static List<Bill> parseBills(String content) {
        List<Bill> result = new ArrayList<>();
        String json = extractJsonAny(content);
        try {
            Object root = new JSONTokener(json).nextValue();
            JSONArray array;
            if (root instanceof JSONArray) {
                array = (JSONArray) root;
            } else if (root instanceof JSONObject) {
                JSONObject object = (JSONObject) root;
                array = object.optJSONArray("bills");
                if (array == null) {
                    Bill single = readBill(object);
                    if (single != null) {
                        result.add(single);
                    }
                    return result;
                }
            } else {
                throw new IllegalStateException("模型返回的结构不是账单列表");
            }
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                Bill bill = readBill(item);
                if (bill != null) {
                    result.add(bill);
                }
            }
        } catch (JSONException e) {
            throw new IllegalStateException("解析账单列表失败：" + e.getMessage(), e);
        }
        return result;
    }

    private static Bill readBill(JSONObject object) {
        if (object.has("is_bill") && !object.optBoolean("is_bill", true)) {
            return null;
        }
        Bill bill = new Bill();
        String merchant = object.optString("merchant", "").trim();
        bill.merchant = merchant.isEmpty() ? "未知商家" : merchant;
        long cents = toCents(object.opt("amount"));
        if (cents < 0) {
            cents = -cents;
        }
        if (cents <= 0) {
            return null;
        }
        bill.amountCents = cents;
        bill.occurredAt = parseTime(object.optString("occurred_at", ""));
        bill.category = normalizeCategory(object.optString("category", ""));

        double confidence = object.optDouble("confidence", 0);
        bill.confidence = confidence > 1 ? confidence / 100.0 : confidence;
        bill.createdAt = System.currentTimeMillis();
        return bill;
    }

    public static Insight parseInsight(String content) {
        Insight insight = new Insight();
        try {
            JSONObject object = new JSONObject(extractJson(content));
            insight.summary = object.optString("summary", "").trim();
            JSONArray tips = object.optJSONArray("tips");
            if (tips != null) {
                for (int i = 0; i < tips.length(); i++) {
                    String tip = tips.optString(i, "").trim();
                    if (!tip.isEmpty()) {
                        insight.tips.add(tip);
                    }
                }
            }
        } catch (JSONException e) {
            throw new IllegalStateException("解析洞察结果失败：" + e.getMessage(), e);
        }
        if (insight.summary.isEmpty() && insight.tips.isEmpty()) {
            throw new IllegalStateException("模型没有返回可用的洞察内容");
        }
        return insight;
    }

    public static Report parseReport(String content) {
        Report report = new Report();
        try {
            JSONObject object = new JSONObject(extractJson(content));
            report.score = Math.max(0, Math.min(100, object.optInt("score", 0)));
            report.verdict = object.optString("verdict", "").trim();
            report.overview = object.optString("overview", "").trim();
            report.structure = object.optString("structure", "").trim();
            report.risk = object.optString("risk", "").trim();
            JSONArray advice = object.optJSONArray("advice");
            if (advice != null) {
                for (int i = 0; i < advice.length(); i++) {
                    String item = advice.optString(i, "").trim();
                    if (!item.isEmpty()) {
                        report.advice.add(item);
                    }
                }
            }
        } catch (JSONException e) {
            throw new IllegalStateException("解析财报结果失败：" + e.getMessage(), e);
        }
        if (report.overview.isEmpty() && report.structure.isEmpty()
                && report.risk.isEmpty() && report.advice.isEmpty()) {
            throw new IllegalStateException("模型没有返回可用的财报内容");
        }
        return report;
    }

    static String extractJson(String content) {
        if (content == null) {
            throw new IllegalStateException("模型返回为空");
        }
        String text = content.trim();
        if (text.startsWith("```")) {
            int firstLine = text.indexOf('\n');
            text = firstLine >= 0 ? text.substring(firstLine + 1) : text;
            int fence = text.lastIndexOf("```");
            if (fence >= 0) {
                text = text.substring(0, fence);
            }
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalStateException("模型返回里找不到 JSON：" + truncate(content, 200));
        }
        return text.substring(start, end + 1);
    }

    /**
     * 批量结果可能是 {"bills":[...]}，也可能直接是 [...]，取更靠前的那个括号。
     */
    static String extractJsonAny(String content) {
        if (content == null) {
            throw new IllegalStateException("模型返回为空");
        }
        String text = content.trim();
        if (text.startsWith("```")) {
            int firstLine = text.indexOf('\n');
            text = firstLine >= 0 ? text.substring(firstLine + 1) : text;
            int fence = text.lastIndexOf("```");
            if (fence >= 0) {
                text = text.substring(0, fence);
            }
        }
        int objectStart = text.indexOf('{');
        int arrayStart = text.indexOf('[');
        int start;
        int end;
        if (arrayStart >= 0 && (objectStart < 0 || arrayStart < objectStart)) {
            start = arrayStart;
            end = text.lastIndexOf(']');
        } else {
            start = objectStart;
            end = text.lastIndexOf('}');
        }
        if (start < 0 || end <= start) {
            throw new IllegalStateException("模型返回里找不到 JSON：" + truncate(content, 200));
        }
        return text.substring(start, end + 1);
    }

    private static long toCents(Object amount) {
        if (amount == null || amount == JSONObject.NULL) {
            return 0;
        }
        String raw = String.valueOf(amount)
                .replace("¥", "")
                .replace("￥", "")
                .replace(",", "")
                .replace("元", "")
                .trim();
        if (raw.isEmpty()) {
            return 0;
        }
        try {
            return new BigDecimal(raw)
                    .multiply(BigDecimal.valueOf(100))
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValue();
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long parseTime(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) {
            return System.currentTimeMillis();
        }
        try {
            return OffsetDateTime.parse(text).toInstant().toEpochMilli();
        } catch (Exception ignored) {
            // 继续尝试本地时间格式
        }
        String normalized = text.replace('T', ' ');
        if (normalized.length() == 10) {
            normalized = normalized + " 00:00";
        }
        String[] patterns = {
                "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm",
                "yyyy/MM/dd HH:mm:ss", "yyyy/MM/dd HH:mm",
                "yyyy年MM月dd日 HH:mm"
        };
        for (String pattern : patterns) {
            SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.CHINA);
            format.setLenient(false);
            try {
                Date date = format.parse(normalized);
                if (date != null) {
                    return date.getTime();
                }
            } catch (ParseException ignored) {
                // 换下一个格式
            }
        }
        return System.currentTimeMillis();
    }

    static String normalizeCategory(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (Bill.isValidCategory(text)) {
            return text;
        }
        if (containsAny(text, "外卖", "餐饮", "饭", "咖啡", "奶茶", "餐厅", "烧烤", "火锅")) {
            return "餐饮外卖";
        }
        if (containsAny(text, "打车", "地铁", "公交", "加油", "滴滴", "高铁", "机票", "停车")) {
            return "交通";
        }
        if (containsAny(text, "淘宝", "京东", "拼多多", "购物", "服饰", "数码")) {
            return "购物";
        }
        if (containsAny(text, "超市", "便利店", "日用品", "洗护", "纸巾")) {
            return "日用";
        }
        if (containsAny(text, "电影", "游戏", "KTV", "会员", "演出", "娱乐")) {
            return "娱乐";
        }
        if (containsAny(text, "医院", "药", "挂号", "体检")) {
            return "医疗";
        }
        if (containsAny(text, "房租", "水费", "电费", "物业", "燃气", "居住")) {
            return "居住";
        }
        return Bill.CATEGORY_OTHER;
    }

    private static boolean containsAny(String text, String... keys) {
        for (String key : keys) {
            if (text.contains(key)) {
                return true;
            }
        }
        return false;
    }

    static String truncate(String text, int limit) {
        if (text == null) {
            return "";
        }
        return text.length() <= limit ? text : text.substring(0, limit) + "...";
    }
}
