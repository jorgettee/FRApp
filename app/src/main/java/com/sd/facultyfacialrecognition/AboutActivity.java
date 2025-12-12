package com.sd.facultyfacialrecognition;

import android.os.Bundle;

public class AboutActivity extends BaseDrawerActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentViewWithDrawer(R.layout.popup_about_us);
    }
}
