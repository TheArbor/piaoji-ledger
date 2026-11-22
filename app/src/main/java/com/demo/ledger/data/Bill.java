package com.demo.ledger.data;

public class Bill {

    public static final String[] CATEGORIES = {
            "餐饮外卖", "交通", "购物", "日用", "娱乐", "医疗", "居住", "其他"
    };

    public static final String CATEGORY_OTHER = "其他";

    public long id;
    public long amountCents;
    public String merchant = "";
    public String category = CATEGORY_OTHER;
    public long occurredAt;
    public String imagePath;
    public String source;
    public String rawResponse;
    public long createdAt;
    public double confidence;

    public static boolean isValidCategory(String c) {
        for (String item : CATEGORIES) {
            if (item.equals(c)) {
                return true;
            }
        }
        return false;
    }
}
