package com.wxpayrecorder;

/**
 * 一条收款记录。字段由用户在需求中明确提出。
 */
public class PayRecord {
    public long id;          // 自增主键
    public String orderNo;   // 订单号（transcationid / transferid），用于去重
    public long amountCent;  // 金额（单位：分），避免浮点误差
    public long timeMillis;  // 收款时间
    public int paySubType;   // 支付子类型 paysubtype
    public String goodsName; // 商品名/描述 (feedesc / title)
    public String memo;      // 对方备注 (pay_memo)
    public String sender;    // 付款方 (payer_username)
    public String feedesc;   // 原始 feedesc 文本（供排查）
    public String rawXml;    // 原始消息，供后续排查/补采

    public double amountYuan() {
        return amountCent / 100.0;
    }
}