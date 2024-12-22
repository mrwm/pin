package de.nproth.pin;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Lists the notes in the database.
 */
public class NotesList extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.notes_list);
    }

    @Override
    protected void onStart() {
        super.onStart();
    }

    @Override
    protected void onStop() {
        super.onStop();
    }

}
