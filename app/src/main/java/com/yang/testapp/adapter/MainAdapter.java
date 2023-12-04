package com.yang.testapp.adapter;

import android.content.Context;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.recyclerview.widget.RecyclerView;

import com.yang.testapp.R;
import com.yang.testapp.databinding.ItemMainBinding;
import com.yang.testapp.model.MainInfo;

import java.util.List;

/**
 * Created by yangjianhui on 2023/12/4.
 */
public class MainAdapter extends RecyclerView.Adapter<MainAdapter.ContentHolder> {

    private List<MainInfo> mData;
    private Context mContext;

    private OnItemClickListener onItemClickListener;

    public MainAdapter(Context context, List<MainInfo> list) {
        this.mContext = context;
        this.mData = list;
    }

    public void setOnItemClickListener(OnItemClickListener listener) {
        this.onItemClickListener = listener;
    }


    public class ContentHolder extends RecyclerView.ViewHolder {
        ItemMainBinding binding;

        public ContentHolder(View itemView) {
            super(itemView);
            binding = ItemMainBinding.bind(itemView);
        }
    }

    @Override
    public ContentHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        return new ContentHolder(ItemMainBinding.inflate(LayoutInflater.from(mContext)).getRoot());
    }

    @Override
    public void onBindViewHolder(ContentHolder holder, int position) {
        MainInfo item = mData.get(position);
        holder.binding.tvLogoDesc.setText(item.text);
        holder.binding.relCard.setTag(R.id.position, position);
        holder.binding.relCard.setTag(R.id.item, item);
        holder.binding.relCard.setOnClickListener(mOnClickListener);

    }

    private View.OnClickListener mOnClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View view) {
            int position = (int) view.getTag(R.id.position);
            MainInfo item = (MainInfo) view.getTag(R.id.item);
            if (onItemClickListener != null) {
                onItemClickListener.itemClick(position, item);
            }
        }
    };

    @Override
    public int getItemCount() {
        if (mData == null) {
            return 0;
        }
        return mData.size();
    }

    public interface OnItemClickListener {
        void itemClick(int position, MainInfo item);
    }
}
