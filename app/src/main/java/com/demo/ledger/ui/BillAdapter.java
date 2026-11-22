package com.demo.ledger.ui;

import android.graphics.Color;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.demo.ledger.R;
import com.demo.ledger.data.Bill;
import com.google.android.material.color.MaterialColors;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class BillAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_BILL = 1;

    private static final class Row {
        final String header;
        final Bill bill;

        Row(String header, Bill bill) {
            this.header = header;
            this.bill = bill;
        }
    }

    private final List<Row> rows = new ArrayList<>();
    private final SimpleDateFormat dayFormat =
            new SimpleDateFormat("M月d日 EEEE", Locale.CHINA);
    private final SimpleDateFormat timeFormat =
            new SimpleDateFormat("HH:mm", Locale.CHINA);

    public void submit(List<Bill> bills) {
        rows.clear();
        String lastDay = null;
        for (Bill bill : bills) {
            String day = dayFormat.format(new Date(bill.occurredAt));
            if (!day.equals(lastDay)) {
                rows.add(new Row(day, null));
                lastDay = day;
            }
            rows.add(new Row(null, bill));
        }
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        return rows.get(position).bill == null ? TYPE_HEADER : TYPE_BILL;
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == TYPE_HEADER) {
            TextView header = new TextView(parent.getContext());
            header.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            header.setPadding(dp(parent, 16), dp(parent, 12), dp(parent, 16), dp(parent, 4));
            header.setTextSize(12);
            header.setTextColor(MaterialColors.getColor(parent.getContext(),
                    com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY));
            header.setGravity(Gravity.START);
            return new HeaderHolder(header);
        }
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_bill, parent, false);
        return new BillHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Row row = rows.get(position);
        if (holder instanceof HeaderHolder) {
            ((HeaderHolder) holder).text.setText(row.header);
            return;
        }
        Bill bill = row.bill;
        BillHolder billHolder = (BillHolder) holder;
        billHolder.merchant.setText(bill.merchant);
        billHolder.meta.setText(bill.category + " · " + timeFormat.format(new Date(bill.occurredAt)));
        billHolder.amount.setText(String.format(Locale.CHINA, "¥%,.2f", bill.amountCents / 100.0));
    }

    private int dp(ViewGroup parent, int value) {
        return Math.round(value * parent.getResources().getDisplayMetrics().density);
    }

    static final class HeaderHolder extends RecyclerView.ViewHolder {
        final TextView text;

        HeaderHolder(TextView view) {
            super(view);
            text = view;
        }
    }

    static final class BillHolder extends RecyclerView.ViewHolder {
        final TextView merchant;
        final TextView meta;
        final TextView amount;

        BillHolder(View view) {
            super(view);
            merchant = view.findViewById(R.id.merchant);
            meta = view.findViewById(R.id.meta);
            amount = view.findViewById(R.id.amount);
        }
    }
}
