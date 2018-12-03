/*
 * Copyright (c) 2018 Zhang Hai <Dreaming.in.Code.ZH@Gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.settings;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import me.zhanghai.android.files.util.FragmentUtils;

public class StandardDirectoriesActivity extends AppCompatActivity {

    @NonNull
    public static Intent makeIntent(@NonNull Context context) {
        return new Intent(context, StandardDirectoriesActivity.class);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Calls ensureSubDecor().
        findViewById(android.R.id.content);

        if (savedInstanceState == null) {
            FragmentUtils.add(StandardDirectoriesActivityFragment.newInstance(), this,
                    android.R.id.content);
        }
    }
}
