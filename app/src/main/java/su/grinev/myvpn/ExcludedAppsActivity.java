package su.grinev.myvpn;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import su.grinev.myvpn.databinding.ActivityExcludedAppsBinding;
import su.grinev.myvpn.settings.SharedPreferencesSettingsProvider;
import su.grinev.myvpn.state.VpnStateManager;

public class ExcludedAppsActivity extends AppCompatActivity {

    private ActivityExcludedAppsBinding binding;
    private SharedPreferencesSettingsProvider settingsProvider;
    private AppSelectionModel model;
    private final Map<String, AppItem> allItemsMap = new HashMap<>();
    private BypassedAdapter bypassedAdapter;
    private SearchAdapter searchAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        binding = ActivityExcludedAppsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        settingsProvider = new SharedPreferencesSettingsProvider(this);
        model = new AppSelectionModel(settingsProvider.getExcludedApps());

        bypassedAdapter = new BypassedAdapter(this::removeFromBypass);
        searchAdapter = new SearchAdapter(this::addToBypass);

        binding.bypassedRecycler.setLayoutManager(new LinearLayoutManager(this));
        binding.bypassedRecycler.setAdapter(bypassedAdapter);
        binding.searchRecycler.setLayoutManager(new LinearLayoutManager(this));
        binding.searchRecycler.setAdapter(searchAdapter);

        binding.searchEdit.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                model.filter(s.toString().trim());
                refreshSearchList();
            }
        });

        binding.searchEdit.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                runSearch();
                return true;
            }
            return false;
        });

        binding.searchInputLayout.setEndIconOnClickListener(v -> runSearch());

        binding.saveButton.setOnClickListener(v -> {
            settingsProvider.saveExcludedApps(model.getSelectedPackages());
            offerReconnectIfNeeded();
        });

        binding.progressBar.setVisibility(View.VISIBLE);
        binding.searchRecycler.setVisibility(View.GONE);

        loadApps();
    }

    private void loadApps() {
        new Thread(() -> {
            PackageManager pm = getPackageManager();
            Intent launcherIntent = new Intent(Intent.ACTION_MAIN);
            launcherIntent.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> resolved = pm.queryIntentActivities(launcherIntent, 0);
            List<AppItem> items = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (ResolveInfo info : resolved) {
                String pkg = info.activityInfo.packageName;
                if (pkg.equals(getPackageName())) continue;
                if (!seen.add(pkg)) continue;
                items.add(new AppItem(pkg, info.loadLabel(pm).toString(), info.loadIcon(pm)));
            }
            Collections.sort(items, (a, b) -> a.label.compareToIgnoreCase(b.label));

            runOnUiThread(() -> {
                for (AppItem item : items) {
                    allItemsMap.put(item.packageName, item);
                }
                List<AppSelectionModel.Entry> entries = new ArrayList<>();
                for (AppItem item : items) {
                    entries.add(new AppSelectionModel.Entry(item.packageName, item.label));
                }
                model.setEntries(entries);

                binding.progressBar.setVisibility(View.GONE);
                binding.searchRecycler.setVisibility(View.VISIBLE);
                refreshAllLists();
            });
        }).start();
    }

    private void addToBypass(String packageName) {
        model.setSelected(packageName, true);
        refreshAllLists();
    }

    private void removeFromBypass(String packageName) {
        model.setSelected(packageName, false);
        refreshAllLists();
    }

    private void refreshAllLists() {
        refreshBypassedList();
        refreshSearchList();
    }

    private void refreshBypassedList() {
        List<AppItem> bypassed = new ArrayList<>();
        for (AppSelectionModel.Entry e : model.getSelectedEntries()) {
            AppItem item = allItemsMap.get(e.packageName);
            if (item != null) bypassed.add(item);
        }
        bypassedAdapter.setItems(bypassed);
        binding.emptyBypassedText.setVisibility(bypassed.isEmpty() ? View.VISIBLE : View.GONE);
        binding.bypassedRecycler.setVisibility(bypassed.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void refreshSearchList() {
        List<AppItem> results = new ArrayList<>();
        for (AppSelectionModel.Entry e : model.getVisibleUnselected()) {
            AppItem item = allItemsMap.get(e.packageName);
            if (item != null) results.add(item);
        }
        searchAdapter.setItems(results);
    }

    private void runSearch() {
        String query = binding.searchEdit.getText() != null
                ? binding.searchEdit.getText().toString().trim() : "";
        model.filter(query);
        refreshSearchList();
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(binding.searchEdit.getWindowToken(), 0);
        binding.searchEdit.clearFocus();
    }

    private void offerReconnectIfNeeded() {
        State state = VpnStateManager.getInstance().getState();
        boolean vpnActive = state == State.CONNECTED || state == State.LIVE
                || state == State.CONNECTING || state == State.WAITING;
        if (!vpnActive) {
            finish();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.excluded_apps_reconnect_title)
                .setMessage(R.string.excluded_apps_reconnect_message)
                .setPositiveButton(R.string.excluded_apps_reconnect_now, (d, w) -> {
                    Intent intent = new Intent(this, MyVpnService.class);
                    intent.setAction(MyVpnService.ACTION_RECONNECT);
                    startService(intent);
                    finish();
                })
                .setNegativeButton(R.string.excluded_apps_reconnect_later, (d, w) -> finish())
                .setCancelable(false)
                .show();
    }

    // ---- Data model ----

    static class AppItem {
        final String packageName;
        final String label;
        final Drawable icon;

        AppItem(String packageName, String label, Drawable icon) {
            this.packageName = packageName;
            this.label = label;
            this.icon = icon;
        }
    }

    // ---- Bypassed apps adapter ----

    static class BypassedAdapter extends RecyclerView.Adapter<BypassedAdapter.ViewHolder> {
        private List<AppItem> items = Collections.emptyList();
        private final Consumer<String> onRemove;

        BypassedAdapter(Consumer<String> onRemove) {
            this.onRemove = onRemove;
        }

        void setItems(List<AppItem> items) {
            this.items = new ArrayList<>(items);
            notifyDataSetChanged();
        }

        @NonNull @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_bypassed_app, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            AppItem item = items.get(position);
            holder.icon.setImageDrawable(item.icon);
            holder.appName.setText(item.label);
            holder.packageName.setText(item.packageName);
            holder.removeButton.setOnClickListener(v -> onRemove.accept(item.packageName));
        }

        @Override public int getItemCount() { return items.size(); }

        static class ViewHolder extends RecyclerView.ViewHolder {
            final ImageView icon;
            final TextView appName;
            final TextView packageName;
            final ImageButton removeButton;

            ViewHolder(View itemView) {
                super(itemView);
                icon = itemView.findViewById(R.id.appIcon);
                appName = itemView.findViewById(R.id.appName);
                packageName = itemView.findViewById(R.id.packageName);
                removeButton = itemView.findViewById(R.id.removeButton);
            }
        }
    }

    // ---- Search results adapter ----

    static class SearchAdapter extends RecyclerView.Adapter<SearchAdapter.ViewHolder> {
        private List<AppItem> items = Collections.emptyList();
        private final Consumer<String> onAdd;

        SearchAdapter(Consumer<String> onAdd) {
            this.onAdd = onAdd;
        }

        void setItems(List<AppItem> items) {
            this.items = new ArrayList<>(items);
            notifyDataSetChanged();
        }

        @NonNull @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_app, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            AppItem item = items.get(position);
            holder.icon.setImageDrawable(item.icon);
            holder.appName.setText(item.label);
            holder.packageName.setText(item.packageName);
            holder.addButton.setOnClickListener(v -> onAdd.accept(item.packageName));
            holder.itemView.setOnClickListener(v -> onAdd.accept(item.packageName));
        }

        @Override public int getItemCount() { return items.size(); }

        static class ViewHolder extends RecyclerView.ViewHolder {
            final ImageView icon;
            final TextView appName;
            final TextView packageName;
            final ImageButton addButton;

            ViewHolder(View itemView) {
                super(itemView);
                icon = itemView.findViewById(R.id.appIcon);
                appName = itemView.findViewById(R.id.appName);
                packageName = itemView.findViewById(R.id.packageName);
                addButton = itemView.findViewById(R.id.addButton);
            }
        }
    }
}
