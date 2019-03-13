package de.nproth.pin;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationManagerCompat;
import android.util.Log;

import de.nproth.pin.util.Timespan;

/**
 * Updates notifications when pins are added / snoozed / deleted
 */
public final class Pinboard {

    private static final String CHANNEL_ID = "de.nproth.pin.notes";

    private static final int SUMMARY_ID = 0;

    private static final String NOTES_GROUP = "de.nproth.pin.NOTES_GROUP";

    private static final int JOB_ID = 23156731;//really just a random number


    private final Context mContext;
    private final NotificationManagerCompat mNotify;
    private final NotificationManager mNManager;
    private final AlarmManager mAlarm;
    private final JobScheduler mScheduler;


    private long mLastChecked = 0;

    private Pinboard(@NonNull Context ctx) {
        mContext = ctx;

        mNManager = (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
        mNotify = NotificationManagerCompat.from(mContext);


        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mScheduler = (JobScheduler) mContext.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            mAlarm = null;
        }
        else {
            mAlarm = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);
            mScheduler = null;
        }

        //Create notification Channel, only needed when running on android 8.0+

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            String cname = mContext.getString(R.string.channel_name);
            String cinfo = mContext.getString(R.string.channel_description);
            int imp = NotificationManager.IMPORTANCE_DEFAULT;//non intrusive TODO adjust importance
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, cname, imp);
            channel.setDescription(cinfo);
            channel.setShowBadge(false);//We want no badges for the pins

            mNManager.createNotificationChannel(channel);
        }
    }

    private void update(long timeNow, String where, String... wargs) {
        final long snoozeDuration = PreferenceManager.getDefaultSharedPreferences(mContext).getLong(NoteActivity.PREFERENCE_SNOOZE_DURATION, NoteActivity.DEFAULT_SNOOZE_DURATION);

        long currentCheck = timeNow;
        long alarmTime = 0;//0 means that no alarm should be set

        Intent idelete = null, isnooze = null, iedit = null, iactivity = new Intent(mContext, NoteActivity.class);
        Cursor db = null;

        try {

            String sLastCheck = Long.toString(mLastChecked);
            //query all rows that were modified or created since we last checked the db, sorted by time of creation
            db = mContext.getContentResolver().query(NotesProvider.Notes.NOTES_URI, new String[]{NotesProvider.Notes._ID, NotesProvider.Notes.TEXT, NotesProvider.Notes.CREATED, NotesProvider.Notes.MODIFIED, NotesProvider.Notes.WAKE_UP},
                    where, wargs, NotesProvider.Notes.CREATED + " DESC");

            Log.d("NotificationService", String.format("%d notes changed, updating notifications...", db.getCount()));

            db.moveToFirst();

            while (!db.isAfterLast()) {

                if (db.isNull(1)) { //check if notification is marked as deleted (text is NULL) and remove the corresponding notification
                    mNotify.cancel(db.getInt(0));//notifications are indexed by their note's _id values
                } else {

                    //check if note is snoozed
                    long wake_up = db.getLong(4);
                    //TODO if a wake_up event is scheduled here, set wake_up time to 0.
                    if (wake_up > currentCheck) {//keep snoozing
                        mNotify.cancel(db.getInt(0));//notifications are indexed by their note's _id values
                        if(alarmTime == 0 || alarmTime > wake_up)
                            alarmTime = wake_up;//schedule alarm to wake this service the next time a note 'wakes up'

                    } else {//else update and show notification

                        //Prepare Pending Intents to snooze or delete notification or edit the note
                        idelete = new Intent(mContext, DeleteNoteReceiver.class);
                        idelete.setData(Uri.withAppendedPath(NotesProvider.Notes.NOTES_URI, Long.toString(db.getLong(0))));

                        isnooze = new Intent(mContext, SnoozeNoteReceiver.class);
                        isnooze.setData(Uri.withAppendedPath(NotesProvider.Notes.NOTES_URI, Long.toString(db.getLong(0))));

                        iedit = new Intent(mContext, NoteActivity.class);
                        iedit.setData(Uri.withAppendedPath(NotesProvider.Notes.NOTES_URI, Long.toString(db.getLong(0))));

                        //create a notification here (previously created notifications that are still visible are just updated)
                        NotificationCompat.Builder builder = new NotificationCompat.Builder(mContext, CHANNEL_ID)
                                .setSmallIcon(R.drawable.ic_pin_statusbar)
                                .setContentTitle(db.getString(1))//use note's text as notification's headline
                                .setWhen(db.getLong(2))//XXX hope this method accepts UTC timestamps; it seemingly does; show time of creation here utilising the db's 'created' column
                                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                                .setOnlyAlertOnce(true)//TODO alert user when a notification woke up
                                //Add actions to delete or snooze note, don't use icons here
                                .addAction(0, mContext.getString(R.string.action_delete), PendingIntent.getBroadcast(mContext, 0, idelete, 0))
                                .addAction(0, mContext.getString(R.string.action_snooze, new Timespan(mContext, PreferenceManager.getDefaultSharedPreferences(mContext).getLong(NoteActivity.PREFERENCE_SNOOZE_DURATION, NoteActivity.DEFAULT_SNOOZE_DURATION)).toString()), PendingIntent.getBroadcast(mContext, 0, isnooze, 0))
                                .addAction(0, mContext.getString(R.string.action_edit), PendingIntent.getActivity(mContext, 0, iedit, 0))
                                .setContentIntent(PendingIntent.getActivity(mContext, 0, iactivity, 0))//show NoteActivity when user clicks on note.
                                .setCategory(NotificationCompat.CATEGORY_REMINDER);

                        isnooze.setAction(SnoozeNoteReceiver.ACTION_NOTIFICATION_DISMISSED);
                        builder.setDeleteIntent(PendingIntent.getBroadcast(mContext, 0, isnooze, 0));//snooze notification when the user dismisses it

                        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                            builder.setGroup(NOTES_GROUP);

                        Notification note = builder.build();

                        //And fire new / updated notification
                        mNotify.notify(db.getInt(0), note);//use cursor's _id column as id for our notification
                    }
                }

                db.moveToNext();
            }
        } catch (Exception e) {
            Log.e("NotificationService", "Unable to create Notifications", e);
        } finally {
            if (db != null)
                db.close();
        }

        //schedule an alarm
        if (alarmTime > 0) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {//use jobScheduler if possible

                long latency = alarmTime - System.currentTimeMillis();
                JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(mContext, NotificationJobService.class))
                        .setMinimumLatency(latency).build();
                mScheduler.schedule(job);
                Log.d("NotificationService", String.format("Set up job running in ~ %dmin or ~ %ds", latency / 1000 / 60, latency / 1000));

            } else {//Or fallback to Alarm Manager
                mAlarm.set(AlarmManager.RTC_WAKEUP, alarmTime, PendingIntent.getBroadcast(mContext, 0, new Intent(mContext, AlarmReceiver.class), 0));

                long mins = (alarmTime - currentCheck) / 1000 / 60;
                long secs = (alarmTime - currentCheck) / 1000;
                Log.d("NotificationService", String.format("Set up alarm triggering on %d UTC in ~ %dmin or ~ %ds", alarmTime, mins, secs));
            }
        }

        //mLastChecked = currentCheck;


        //on pre N devices our notification actions are no longer accessible when they are summarized. Just keep distinct notifications on older devices.
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            //Now build a summary notification for the group
            //query all visible texts from other notifications
            db = null;
            try {
                db = mContext.getContentResolver().query(NotesProvider.Notes.NOTES_URI, new String[]{NotesProvider.Notes.TEXT, NotesProvider.Notes.CREATED, NotesProvider.Notes.WAKE_UP},
                        "text IS NOT NULL AND wake_up <= ?", new String[]{Long.toString(currentCheck)}, NotesProvider.Notes.CREATED + " DESC");

                int count = db.getCount();

                if (count > 0) {
                    //Create group summary
                    NotificationCompat.InboxStyle style = new NotificationCompat.InboxStyle();
                    style.setBigContentTitle(mContext.getResources().getQuantityString(R.plurals.notification_summary_title, count, count));


                    long when = currentCheck;
                    String str;

                    db.moveToFirst();
                    while (!db.isAfterLast()) {//XXX Inbox style only supports up to 5 lines it says in the documentation, but I think more lines do also work
                        str = db.getString(0);
                        style.addLine(str);
                        when = Math.min(when, db.getLong(1));//find oldest reminder
                        db.moveToNext();
                    }

                    Notification note = new NotificationCompat.Builder(mContext, CHANNEL_ID)
                            .setSmallIcon(R.drawable.ic_pin_statusbar)
                            .setStyle(style)
                            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                            .setWhen(when)
                            .setOnlyAlertOnce(true)
                            .setContentIntent(PendingIntent.getActivity(mContext, 0, new Intent(mContext, NoteActivity.class), 0))//show NoteActivity when user clicks on note.
                            .setGroup(NOTES_GROUP)
                            .setGroupSummary(true)
                            .setCategory(NotificationCompat.CATEGORY_REMINDER)
                            .build();

                    mNotify.notify(SUMMARY_ID, note);
                } else
                    mNotify.cancel(SUMMARY_ID);

            } catch (Exception e) {

            } finally {
                if (db != null)
                    db.close();
            }
        }
    }

    public void updateAll() {
        //query all rows
        update(System.currentTimeMillis(), null);
    }

    public void updateVisible() {
        long now = System.currentTimeMillis();
        String sNow = Long.toString(now);
        //query all rows that are neither snoozed nor deleted and thus visible
        update(now, "text IS NOT NULL AND wake_up <= ?", sNow);
    }

    public void updateChanged() {
        long now = System.currentTimeMillis();
        String sLastCheck = Long.toString(mLastChecked);
        //query all rows that were modified or created since we last checked the db, sorted by time of creation
        update(now, "modified >= ? OR wake_up >= ?", sLastCheck, sLastCheck);
        mLastChecked = now;
    }

    public static Pinboard get(Context ctx) {
        if(ctx == null)
            throw new NullPointerException("Cannot acquire instance of singleton 'Pinboard': Context is NULL ");
        return new Pinboard(ctx);
    }
}