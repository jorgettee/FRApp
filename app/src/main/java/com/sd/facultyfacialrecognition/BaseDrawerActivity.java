package com.sd.facultyfacialrecognition;

import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.annotation.LayoutRes;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.google.android.material.navigation.NavigationView;

/**
 * Shared drawer host so every screen can open the navigation drawer by swiping from the left edge.
 */
public abstract class BaseDrawerActivity extends AppCompatActivity implements NavigationView.OnNavigationItemSelectedListener {

    protected DrawerLayout drawerLayout;
    protected NavigationView navigationView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    protected void setContentViewWithDrawer(@LayoutRes int contentLayoutId) {
        super.setContentView(R.layout.drawer_menu);

        FrameLayout container = findViewById(R.id.drawer_content_container);
        getLayoutInflater().inflate(contentLayoutId, container, true);

        drawerLayout = findViewById(R.id.drawer_layout);
        navigationView = findViewById(R.id.nav_view);
        navigationView.setNavigationItemSelectedListener(this);
    }

    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.nav_home && !(this instanceof HomeActivity)) {
            startActivity(new Intent(this, HomeActivity.class));
        } else if (id == R.id.nav_schedule && !(this instanceof AdminActivity)) {
            startActivity(new Intent(this, AdminActivity.class));
        } else if (id == R.id.nav_about) {
            Toast.makeText(this, "About page coming soon.", Toast.LENGTH_SHORT).show();
        } else if (id == R.id.nav_exit) {
            finishAffinity();
        }

        drawerLayout.closeDrawer(GravityCompat.START);
        return true;
    }

    @Override
    public void onBackPressed() {
        if (drawerLayout != null && drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START);
        } else {
            super.onBackPressed();
        }
    }
}

