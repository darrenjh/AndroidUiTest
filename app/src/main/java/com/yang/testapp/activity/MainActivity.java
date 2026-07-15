package com.yang.testapp.activity;

import android.app.Dialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.text.TextPaint;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;

import com.yang.testapp.R;
import com.yang.testapp.adapter.MainAdapter;
import com.yang.testapp.databinding.ActivityMainBinding;
import com.yang.testapp.model.MainInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by yangjianhui on 2023/12/4.
 */
public class MainActivity extends AppCompatActivity {
    private ActivityMainBinding binding;
    private MainInfo currentInfo;

    /**
     * 初始化首页列表，并挂载生物特征采集入口按钮。
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(LayoutInflater.from(this));
        setContentView(binding.getRoot());
        bindCollectEntries();
        initView(30, binding, new Runnable() {
            @Override
            public void run() {
                showDialog();
            }
        });
    }

    /**
     * 绑定首页的人脸和声纹采集入口，点击后进入各自独立采集页。
     */
    private void bindCollectEntries() {
        binding.btnFaceCollect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startActivity(new Intent(MainActivity.this, FaceCollectActivity.class));
            }
        });
        binding.btnFaceCollectMvvm.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startActivity(new Intent(MainActivity.this, FaceCollectMvvmActivity.class));
            }
        });
        binding.btnVoiceCollect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startActivity(new Intent(MainActivity.this, VoiceCollectActivity.class));
            }
        });
    }

    private void initView(int count,ActivityMainBinding binding,Runnable runnable) {
        List<MainInfo> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(new MainInfo("Item data " + i));
        }
        MainAdapter mMainAdapter = new MainAdapter(this, list);
        mMainAdapter.setOnItemClickListener(new MainAdapter.OnItemClickListener() {
            @Override
            public void itemClick(int position, MainInfo item) {
                currentInfo=item;
                Log.d("tag", "Click item, position:" + position + ",item:" + item.text);
                runnable.run();
            }
        });
        binding.recyclerView.setLayoutManager(new GridLayoutManager(this, 3));
        binding.recyclerView.setAdapter(mMainAdapter);
    }

    private void showDialog() {
        Dialog dialog = new Dialog(this);
        ActivityMainBinding binding = ActivityMainBinding.inflate(LayoutInflater.from(this));
        dialog.setContentView(binding.getRoot());
        WindowManager.LayoutParams attributes = dialog.getWindow().getAttributes();
        attributes.width=1000;
        attributes.height=600;
        binding.tvTitle.setText(currentInfo.text + " is clicked");
        binding.tvTitle.setTextColor(Color.RED);
        binding.tvTitle.setBackgroundColor(Color.YELLOW);
        TextPaint tp = new TextPaint();
        tp.setFakeBoldText(true);
        binding.tvTitle.setPaintFlags(binding.tvTitle.getPaintFlags() | Paint.FAKE_BOLD_TEXT_FLAG);
        initView(8, binding, new Runnable() {
            @Override
            public void run() {
                binding.tvTitle.setText(currentInfo.text + " click at dialog!");
            }
        });
        binding.tvTitle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                dialog.dismiss();
            }
        });
        dialog.show();
    }


}
