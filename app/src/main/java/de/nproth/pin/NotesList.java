package de.nproth.pin;

import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import de.nproth.pin.util.NotesRecyclerAdapter;

/**
 * Lists the notes in the database.
 */
public class NotesList extends AppCompatActivity implements NotesRecyclerAdapter.ItemClickListener, NotesRecyclerAdapter.ItemLongClickListener{

    private NotesRecyclerAdapter mAdapter;

    @Override
    public void onItemClick(View view, int position) {
        Toast.makeText(this, "You clicked " + mAdapter.getItem(position) + " on row number " + position, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onItemLongClick(View view, int position) {
        Toast.makeText(this, "You long clicked " + mAdapter.getItem(position) + " on row number " + position, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.notes_list);

        List<String> note_ids;
        List<String> note_texts = null;
        try (Cursor db_ids = getContentResolver().query(NotesProvider.Notes.NOTES_URI, new String[]{NotesProvider.Notes._ID}, NotesProvider.Notes.TEXT + " NOT NULL", null, NotesProvider.Notes.CREATED + " DESC")) {
            //query all rows for id numbers
            Log.d("NoteList", "Query returned " + db_ids.getCount() + " rows");

            //get all id numbers
            note_ids = new ArrayList<>();
            if (db_ids.moveToFirst()) {
                note_ids.add(db_ids.getString(db_ids.getColumnIndex(NotesProvider.Notes._ID)));
                while (db_ids.moveToNext()) {
                    note_ids.add(db_ids.getString(db_ids.getColumnIndex(NotesProvider.Notes._ID)));
                }
            }
            Log.i("NoteList", "Note id numbers: " + Arrays.toString(note_ids.toArray()));

            //Get all the notes and add them to the recycler view
            note_texts = new ArrayList<>();
            for (int i = 0; i < note_ids.size(); i++) {
                Uri uri = Uri.parse(NotesProvider.Notes.NOTES_URI + "/" + note_ids.get(i));
                Cursor c = getContentResolver().query(uri, new String[] { NotesProvider.Notes.TEXT }, null, null, null);
                if(c == null || c.getCount() != 1) {
                    Log.e("NoteList", String.format("Could not query text for uri '%s'", uri));
                } else {
                    c.moveToFirst();
                    note_texts.add(c.getString(0));
                }

                if(c != null)
                    c.close();
            }
            Log.i("NoteList", "Note texts: " + Arrays.toString(note_texts.toArray()));
        } catch (Exception e) {
            Log.e("NoteList", "Unable to query database", e);
        }

        // set up the RecyclerView
        RecyclerView recyclerView = findViewById(R.id.note_recycler);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        mAdapter = new NotesRecyclerAdapter(this, note_texts);
        mAdapter.setClickListener(this);
        mAdapter.setLongClickListener(this);
        recyclerView.setAdapter(mAdapter);

    }


}
