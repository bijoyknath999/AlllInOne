package com.allinone.app;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.allinone.app.databinding.ActivityReminderBinding;
import com.allinone.app.reminder.Reminder;
import com.allinone.app.reminder.ReminderAdapter;
import com.allinone.app.reminder.ReminderDb;
import com.allinone.app.reminder.ReminderReceiver;
import com.allinone.app.reminder.ReminderScheduler;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

public class ReminderActivity extends AppCompatActivity {

    private ActivityReminderBinding binding;
    private ReminderDb db;
    private ReminderAdapter adapter;
    private final List<Reminder> reminders = new ArrayList<>();

    // Pending image for the open dialog
    private Uri pendingImageUri = null;
    private ImageView dialogImgPreview = null;
    private TextView dialogImgName = null;
    private ImageView dialogImgRemove = null;

    private final ActivityResultLauncher<String> imagePicker =
        registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null) {
                try {
                    getContentResolver().takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception ignored) {}
                pendingImageUri = uri;
                if (dialogImgPreview != null) {
                    dialogImgPreview.setImageURI(uri);
                    dialogImgPreview.setVisibility(View.VISIBLE);
                }
                if (dialogImgName != null) {
                    String path = uri.getLastPathSegment();
                    dialogImgName.setText(path != null ? path : "Image selected");
                    dialogImgName.setTextColor(getResources().getColor(R.color.text_primary, null));
                }
                if (dialogImgRemove != null) dialogImgRemove.setVisibility(View.VISIBLE);
            }
        });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        binding = ActivityReminderBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            binding.getRoot().setPadding(0, bars.top, 0, bars.bottom);
            return insets;
        });

        db = new ReminderDb(this);
        ReminderReceiver.createChannels(this);

        binding.btnBack.setOnClickListener(v -> finish());
        setupRecyclerView();
        binding.fabAdd.setOnClickListener(v -> showAddDialog());
        load();
        animateIn();
    }

    private void setupRecyclerView() {
        adapter = new ReminderAdapter(reminders, new ReminderAdapter.Listener() {
            @Override
            public void onToggle(Reminder r, boolean enabled) {
                r.enabled = enabled;
                db.setEnabled(r.id, enabled);
                if (enabled) {
                    checkExactAlarmPermission();
                    ReminderScheduler.schedule(ReminderActivity.this, r);
                } else {
                    ReminderScheduler.cancel(ReminderActivity.this, r);
                }
                load();
            }

            @Override
            public void onDelete(Reminder r) {
                new AlertDialog.Builder(ReminderActivity.this)
                    .setTitle("Delete Reminder")
                    .setMessage("Remove \"" + r.title + "\"?")
                    .setPositiveButton("Delete", (d, w) -> {
                        ReminderScheduler.cancel(ReminderActivity.this, r);
                        db.delete(r.id);
                        load();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            }
        });
        binding.rvReminders.setLayoutManager(new LinearLayoutManager(this));
        binding.rvReminders.setAdapter(adapter);
    }

    private void load() {
        reminders.clear();
        reminders.addAll(db.getAll());
        adapter.notifyDataSetChanged();
        binding.tvEmptyHint.setVisibility(reminders.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void showAddDialog() {
        pendingImageUri = null;

        View view = LayoutInflater.from(this).inflate(R.layout.dialog_add_reminder, null);

        EditText etTitle         = view.findViewById(R.id.et_title);
        EditText etMessage       = view.findViewById(R.id.et_message);
        RadioGroup rgInterval    = view.findViewById(R.id.rg_interval);
        EditText etMinutes       = view.findViewById(R.id.et_minutes);
        LinearLayout llDatetime  = view.findViewById(R.id.ll_datetime);
        TextView tvDateOnce      = view.findViewById(R.id.tv_date_once);
        TextView tvTimeOnce      = view.findViewById(R.id.tv_time_once);
        LinearLayout llTimeRecur = view.findViewById(R.id.ll_time_recurring);
        TextView tvTimeRecur     = view.findViewById(R.id.tv_time_recurring);
        TextView btnPickImage    = view.findViewById(R.id.btn_pick_image);
        CheckBox cbSound         = view.findViewById(R.id.cb_sound);
        CheckBox cbVibrate       = view.findViewById(R.id.cb_vibrate);

        dialogImgPreview = view.findViewById(R.id.iv_image_preview);
        dialogImgName    = view.findViewById(R.id.tv_image_name);
        dialogImgRemove  = view.findViewById(R.id.iv_img_remove);

        // One-time picker calendar
        Calendar selectedDateTime = Calendar.getInstance();
        selectedDateTime.add(Calendar.HOUR_OF_DAY, 1);
        selectedDateTime.set(Calendar.SECOND, 0);

        // Recurring time-of-day
        final int[] recurHour   = {8};
        final int[] recurMinute = {0};

        SimpleDateFormat dateFmt = new SimpleDateFormat("EEE, MMM dd yyyy", Locale.getDefault());
        SimpleDateFormat timeFmt = new SimpleDateFormat("hh:mm a", Locale.getDefault());
        SimpleDateFormat t12Fmt  = new SimpleDateFormat("h:mm a", Locale.getDefault());

        tvDateOnce.setText(dateFmt.format(selectedDateTime.getTime()));
        tvTimeOnce.setText(timeFmt.format(selectedDateTime.getTime()));

        // Date picker for once
        tvDateOnce.setOnClickListener(v -> new DatePickerDialog(this, (picker, y, m, d) -> {
            selectedDateTime.set(Calendar.YEAR, y);
            selectedDateTime.set(Calendar.MONTH, m);
            selectedDateTime.set(Calendar.DAY_OF_MONTH, d);
            tvDateOnce.setText(dateFmt.format(selectedDateTime.getTime()));
        }, selectedDateTime.get(Calendar.YEAR),
           selectedDateTime.get(Calendar.MONTH),
           selectedDateTime.get(Calendar.DAY_OF_MONTH)).show());

        // Time picker for once
        tvTimeOnce.setOnClickListener(v -> new TimePickerDialog(this, (picker, hour, min) -> {
            selectedDateTime.set(Calendar.HOUR_OF_DAY, hour);
            selectedDateTime.set(Calendar.MINUTE, min);
            tvTimeOnce.setText(timeFmt.format(selectedDateTime.getTime()));
        }, selectedDateTime.get(Calendar.HOUR_OF_DAY),
           selectedDateTime.get(Calendar.MINUTE), false).show());

        // Time picker for recurring
        tvTimeRecur.setOnClickListener(v -> new TimePickerDialog(this, (picker, hour, min) -> {
            recurHour[0]   = hour;
            recurMinute[0] = min;
            Calendar c = Calendar.getInstance();
            c.set(Calendar.HOUR_OF_DAY, hour);
            c.set(Calendar.MINUTE, min);
            tvTimeRecur.setText(t12Fmt.format(c.getTime()));
        }, recurHour[0], recurMinute[0], false).show());

        // Visibility logic
        rgInterval.setOnCheckedChangeListener((g, id) -> {
            boolean isOnce    = id == R.id.rb_once;
            boolean isMinutes = id == R.id.rb_minutes;
            boolean isRecur   = id == R.id.rb_daily || id == R.id.rb_weekly
                             || id == R.id.rb_biweekly || id == R.id.rb_monthly;

            llDatetime.setVisibility(isOnce    ? View.VISIBLE : View.GONE);
            etMinutes.setVisibility( isMinutes ? View.VISIBLE : View.GONE);
            llTimeRecur.setVisibility(isRecur  ? View.VISIBLE : View.GONE);
        });
        // Default: daily → show time picker
        rgInterval.check(R.id.rb_daily);

        // Image picker
        btnPickImage.setOnClickListener(v -> imagePicker.launch("image/*"));
        dialogImgRemove.setOnClickListener(v -> {
            pendingImageUri = null;
            dialogImgPreview.setVisibility(View.GONE);
            dialogImgRemove.setVisibility(View.GONE);
            dialogImgName.setText("No image");
            dialogImgName.setTextColor(getResources().getColor(R.color.text_hint, null));
        });

        new AlertDialog.Builder(this)
            .setView(view)
            .setOnDismissListener(d -> {
                dialogImgPreview = null;
                dialogImgName    = null;
                dialogImgRemove  = null;
            })
            .setPositiveButton("Add", (d, w) -> {
                String title = etTitle.getText().toString().trim();
                if (title.isEmpty()) {
                    Toast.makeText(this, "Enter a title", Toast.LENGTH_SHORT).show();
                    return;
                }

                int checkedId    = rgInterval.getCheckedRadioButtonId();
                int intervalType;
                int intervalValue = 0;

                if (checkedId == R.id.rb_once) {
                    if (selectedDateTime.getTimeInMillis() <= System.currentTimeMillis()) {
                        Toast.makeText(this, "Please pick a future date and time", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    intervalType = Reminder.TYPE_ONCE;
                } else if (checkedId == R.id.rb_minutes) {
                    intervalType = Reminder.TYPE_MINUTES;
                    String s = etMinutes.getText().toString().trim();
                    try { intervalValue = Integer.parseInt(s); } catch (Exception e) { intervalValue = 30; }
                    if (intervalValue < 1) intervalValue = 1;
                } else if (checkedId == R.id.rb_hourly) {
                    intervalType = Reminder.TYPE_HOURLY;
                } else if (checkedId == R.id.rb_weekly) {
                    intervalType = Reminder.TYPE_WEEKLY;
                } else if (checkedId == R.id.rb_biweekly) {
                    intervalType = Reminder.TYPE_BIWEEKLY;
                } else if (checkedId == R.id.rb_monthly) {
                    intervalType = Reminder.TYPE_MONTHLY;
                } else {
                    intervalType = Reminder.TYPE_DAILY;
                }

                Reminder r = new Reminder();
                r.title        = title;
                r.message      = etMessage.getText().toString().trim();
                r.intervalType = intervalType;
                r.intervalValue= intervalValue;
                r.intervalMs   = Reminder.computeIntervalMs(intervalType, intervalValue);
                r.enabled      = true;
                r.sound        = cbSound.isChecked();
                r.vibrate      = cbVibrate.isChecked();
                r.imageUri     = pendingImageUri != null ? pendingImageUri.toString() : null;

                if (intervalType == Reminder.TYPE_ONCE) {
                    r.nextFireMs = selectedDateTime.getTimeInMillis();
                    r.fireHour   = selectedDateTime.get(Calendar.HOUR_OF_DAY);
                    r.fireMinute = selectedDateTime.get(Calendar.MINUTE);
                } else if (intervalType == Reminder.TYPE_MINUTES || intervalType == Reminder.TYPE_HOURLY) {
                    r.fireHour   = 0;
                    r.fireMinute = 0;
                    r.nextFireMs = 0; // computeNextFireMs handles it
                } else {
                    r.fireHour   = recurHour[0];
                    r.fireMinute = recurMinute[0];
                    r.nextFireMs = 0; // scheduler will compute via computeNextFireMs
                }

                long newId = db.insert(r);
                r.id = newId;

                checkExactAlarmPermission();
                ReminderScheduler.schedule(this, r);
                load();
                Toast.makeText(this, "Reminder set: " + r.intervalLabel(), Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void checkExactAlarmPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            android.app.AlarmManager am = (android.app.AlarmManager) getSystemService(ALARM_SERVICE);
            if (!am.canScheduleExactAlarms()) {
                new AlertDialog.Builder(this)
                    .setTitle("Exact Alarms")
                    .setMessage("For precise reminder timing, allow exact alarms in Settings.")
                    .setPositiveButton("Open Settings", (d, w) -> {
                        Intent i = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            Uri.parse("package:" + getPackageName()));
                        startActivity(i);
                    })
                    .setNegativeButton("Skip", null)
                    .show();
            }
        }
    }

    private void animateIn() {
        Animation a = AnimationUtils.loadAnimation(this, R.anim.slide_up_fade_in);
        binding.rvReminders.startAnimation(a);
    }
}