/**
 * This widget holds the main screen text.
 *
 * It is an edit text box so the user can focus the keyboard on it when
 * he/she wants to send to the host.
 *
 * Most of the EditText mechanisms are gutted:
 *  1) We intercept all keyboard strokes and do not allow them to alter
 *     the window contents directly, but pass them on to the host for
 *     processing.  The host then sends back any updates it wants to
 *     make to the screen text contents.
 *  2) We provide our own text editing and drawing, as the supplied ones
 *     are too clumsy for our use.  This includes scrolling.  As such,
 *     none of the normal setText() and append() calls work, instead,
 *     Incoming(), Clear() and RenderText() are provided.
 *
 * But we stick with an EditText widget so we get the soft keyboard stuff
 * and all the paint setup.
 */

//    Copyright (C) 2014, Mike Rieker, Beverly, MA USA
//    www.outerworldapps.com
//
//    This program is free software; you can redistribute it and/or modify
//    it under the terms of the GNU General Public License as published by
//    the Free Software Foundation; version 2 of the License.
//
//    This program is distributed in the hope that it will be useful,
//    but WITHOUT ANY WARRANTY; without even the implied warranty of
//    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
//    GNU General Public License for more details.
//
//    EXPECT it to FAIL when someone's HeALTh or PROpeRTy is at RISk.
//
//    You should have received a copy of the GNU General Public License
//    along with this program; if not, write to the Free Software
//    Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
//
//    http://www.gnu.org/licenses/gpl-2.0.html

package com.outerworldapps.sshclient;


import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.ClipboardManager;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;

import com.jcraft.jsch.ChannelShell;

import java.util.concurrent.atomic.AtomicBoolean;

public class ScreenTextView extends EditText
        implements TextWatcher {
    public static final String TAG = "SshClient";

    public static final int CURSOR_HALFCYCLE_MS = 512;

    private AtomicBoolean invalTextImmPend = new AtomicBoolean (false);
    private boolean frozen;                      // indicates frozen mode
    private boolean invalTextDelPend;
    private boolean readingkb;                   // set when reading keyboard text buffer (to handle recursion)
    private boolean selectActive;                // the 'b' key has been pressed to begin text selection
    private float savedTextSize;                 // most recently set text size (don't trust getTextSize ())
    private int charWidthInPixels;               // width of one character
    private int lastsentptychrcols;
    private int lastsentptychrrows;
    private int lastsentptypixcols;
    private int lastsentptypixrows;
    private int lineHeightInPixels;              // height of one line of text
    private int numVisibleChars;                 // number of chars (ie width) that can fit in one line of current window
    private int numVisibleLines;                 // number of lines (ie height) that can fit in current window
    private int selectBeg;                       // select range beginning offset in theWholeText
    private int selectCursor;                    // offset in theWholeText where select cursor is
    private int selectEnd;                       // select range end offset in theWholeText
    private int textColor;                       // current foreground color for text
    private int[] visibleLineBegs = new int[1];  // offsets of beginning of visible lines in theWholeText
    private int[] visibleLineEnds = new int[1];  // offsets of end of visible lines in theWholeText
    private MySession session;                   // what session we are part of
    private Paint cursorPaint;                   // used to paint cursor
    private Paint selectPaint;                   // background paint for selected text
    private Paint showeolPaint;                  // paints the EOL character
    private ScreenTextBuffer screenTextBuffer;   // where we get shell screen data from
    private SshClient sshclient;                 // what activity we are part of
    private STPanning panning;                   // used for scrolling based on mouse movements

    public ScreenTextView (MySession ms, ScreenTextBuffer stb)
    {
        super (ms.getSshClient ());
        session   = ms;
        sshclient = ms.getSshClient ();
        screenTextBuffer = stb;

        setTypeface (Typeface.MONOSPACE);
        setInputType (InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        setSingleLine (false);
        setHorizontallyScrolling (false);
        LoadSettings ();
        addTextChangedListener (this);

        panning = new STPanning (sshclient);

        cursorPaint = new Paint ();

        selectPaint = new Paint ();
        selectPaint.setColor (Color.GRAY);
        selectPaint.setStyle (Paint.Style.FILL);

        showeolPaint = new Paint ();
        showeolPaint.setTypeface (Typeface.MONOSPACE);

        screenTextBuffer.SetChangeNotification (new Runnable () {
            @Override
            public void run ()
            {
                if (!invalTextImmPend.getAndSet (true)) {
                    session.getScreendatahandler ().sendEmptyMessage (ScreenDataHandler.INVALTEXT);
                }
            }
        });
    }

    /**
     * Set up text attributes from settings file.
     */
    public void LoadSettings ()
    {
        Settings settings = sshclient.getSettings ();

        // set up possibly new size
        screenTextBuffer.SetRingSize (settings.max_chars.GetValue ());

        // now that array is in place (in case of redraw triggers herein), set up everything else
        int colors  = settings.txt_colors.GetValue ();
        int fgcolor = settings.fgcolors[colors];
        int bgcolor = settings.bgcolors[colors];
        textColor   = frozen ? bgcolor : fgcolor;
        setBackgroundColor (frozen ? fgcolor : bgcolor);
        setTextSize (settings.font_size.GetValue ());
    }

    /**
     * Get ready to start a new session.
     */
    public void Reset ()
    {
        // force sending setPty() to host as soon as we know screen dimensions
        lastsentptychrcols = -1;
        lastsentptychrrows = -1;
        lastsentptypixcols = -1;
        lastsentptypixrows = -1;
    }

    /**
     * Freeze the screen, so things like text selection will work.
     * Prevents any updates or scrolling while frozen by blocking receive from the host
     * (there might be a couple more updates draining through).
     */
    public void FreezeThaw ()
    {
        if (!frozen && (charWidthInPixels > 0) && (lineHeightInPixels > 0)) {

            // freeze, halt receiver thread
            frozen = true;
            screenTextBuffer.SetFrozen (true);

            // put colors in reverse
            Settings settings = sshclient.getSettings ();
            int colors = settings.txt_colors.GetValue ();
            textColor = settings.bgcolors[colors];
            setBackgroundColor (settings.fgcolors[colors]);

            // reset select range
            selectActive = false;
            selectBeg = selectCursor = selectEnd = screenTextBuffer.insertpoint;

            // get stuff redrawn in reversed colors
            invalidate ();
        } else {
            ThawIt ();
        }
    }

    public void ThawIt ()
    {
        // thaw, release receiver thread
        frozen = false;
        screenTextBuffer.SetFrozen (false);

        // reset any active select range
        selectActive = false;
        selectBeg = selectEnd = 0;

        // put colors back to normal
        Settings settings = sshclient.getSettings ();
        int colors = settings.txt_colors.GetValue ();
        textColor = settings.fgcolors[colors];
        setBackgroundColor (settings.bgcolors[colors]);

        // get stuff redrawn in reversed colors
        invalidate ();
    }

    public boolean IsFrozen () { return frozen; }

    /******************************\
     *  Incoming data processing  *
    \******************************/

    /**
     * Clear the screen.
     */
    public void Clear ()
    {
        if (!frozen) {
            screenTextBuffer.ClearScreen ();
        }
    }

    /**
     * Some text was just received, so tell Android to call onDraw().
     * Or maybe we just want to flip the cursor.
     */
    public void InvalTextReceived ()
    {
        if (!invalTextImmPend.getAndSet (false)) {
            invalTextDelPend = false;
        }
        invalidate ();
    }

    /**
     * We were invalidated via invalidate(), so draw all the text to the canvas.
     */
    @Override
    protected void onDraw (Canvas canvas)
    {
        long uptimemillis = SystemClock.uptimeMillis ();

        synchronized (screenTextBuffer) {
            if (screenTextBuffer.needToRender) {
                RenderText ();
                screenTextBuffer.needToRender = false;
                screenTextBuffer.renderCursor = -1;
            }

            Paint paint = getPaint ();
            paint.setColor (textColor);

            final char[] twt = screenTextBuffer.twt;
            final int    twb = screenTextBuffer.twb;
            final int    twm = screenTextBuffer.twm;

            int underhang  = (int)Math.ceil (paint.descent ());
            int lineheight = getLineHeight ();
            int top  = getPaddingTop ();
            int left = getPaddingLeft ();

            int selbeg = (selectBeg <= selectEnd) ? selectBeg : selectEnd;
            int selend = (selectEnd >= selectBeg) ? selectEnd : selectBeg;

            int cursor = frozen ? selectCursor : screenTextBuffer.insertpoint;

            for (int i = 0; i < numVisibleLines; i ++) {

                // y co-ordinate at bottom of text
                top += lineheight;

                // get offsets in theWholeText to be drawn for the line
                int linebeg = visibleLineBegs[i];
                int lineend = visibleLineEnds[i];
                if (lineend > linebeg) {

                    // see if it intersects the current select range
                    if (selectActive && (linebeg < selend) && (lineend > selbeg)) {

                        // if so, highlight the selected characters by doing a gray background
                        int intersectbeg = (linebeg > selbeg) ? linebeg : selbeg;
                        int intersectend = (lineend < selend) ? lineend : selend;
                        canvas.drawRect (
                                left + charWidthInPixels * (intersectbeg - linebeg),
                                top + underhang - lineheight,
                                left + charWidthInPixels * (intersectend - linebeg),
                                top + underhang,
                                selectPaint
                        );
                    }

                    // maybe there is a newline char on the end for show_eols mode
                    if (twt[(lineend-1+twb)&twm] == '\n') {
                        -- lineend;

                        showeolPaint.setColor    (textColor);
                        showeolPaint.setTextSize (savedTextSize);
                        float fudge = paint.measureText ("M", 0, 1) / showeolPaint.measureText ("M", 0, 1);
                        showeolPaint.setTextSize (savedTextSize * fudge / 2);

                        int x = left + charWidthInPixels * (lineend - linebeg);
                        int y = top - lineHeightInPixels / 2;
                        canvas.drawText ("N", 0, 1, x, y, showeolPaint);

                        x += charWidthInPixels  / 2;
                        y += lineHeightInPixels / 2;
                        canvas.drawText ("L", 0, 1, x, y, showeolPaint);
                    }

                    // now drawr the printable text
                    if (lineend > linebeg) {
                        int lbwrapped  = (twb + linebeg) & twm;
                        int lewrapped  = (twb + lineend - 1) & twm;
                        if (lewrapped >= lbwrapped) {
                            canvas.drawText (twt, lbwrapped, lineend - linebeg, left, top, paint);
                        } else {
                            int onend = screenTextBuffer.tws - lbwrapped;
                            canvas.drawText (twt, lbwrapped, onend, left, top, paint);
                            canvas.drawText (
                                    twt,
                                    0,
                                    ++ lewrapped,
                                    left + charWidthInPixels * onend,
                                    top,
                                    paint
                            );
                        }
                    }
                }

                // maybe drawr a cursor
                if ((cursor >= linebeg) &&                                                   // has to be on or after first char on line
                        (cursor <= lineend) &&                                               // has to be on or before last char on line
                        // ...can also be right after last char on line
                        (cursor < linebeg + numVisibleChars) &&                              // can't be off visible width of screen
                        (uptimemillis % (2 * CURSOR_HALFCYCLE_MS) < CURSOR_HALFCYCLE_MS)) {  // make it blink
                    cursorPaint.setColor (textColor);
                    int x = (cursor - linebeg) * charWidthInPixels + left;
                    switch (sshclient.getSettings ().cursor_style.GetValue ()) {
                        case 0: {  // line
                            canvas.drawLine (x - 2, top + underhang - lineheight, x - 2, top + underhang, cursorPaint);
                            break;
                        }
                        case 1: {  // box
                            cursorPaint.setStyle (Paint.Style.STROKE);
                            canvas.drawRect (x - 2, top + underhang - lineheight, x + charWidthInPixels, top + underhang, cursorPaint);
                            break;
                        }
                        case 2: {  // block
                            cursorPaint.setStyle (Paint.Style.FILL);
                            canvas.drawRect (x - 2, top + underhang - lineheight, x + charWidthInPixels, top + underhang, cursorPaint);
                            break;
                        }
                    }
                }
            }
        }

        // maybe call back in a a little while to flip cursor on or off
        if (!invalTextDelPend) {
            invalTextDelPend = true;
            session.getScreendatahandler ().sendEmptyMessageDelayed (
                    ScreenDataHandler.INVALTEXT,
                    CURSOR_HALFCYCLE_MS - (uptimemillis % CURSOR_HALFCYCLE_MS)
            );
        }
    }

    /**
     * Map theWholeText to the screen, using whatever scrolling offsets are in effect.
     * renderCursor < 0: leave scrolling alone
     *             else: adjust scrolling such that cursor position is visible
     */
    private void RenderText ()
    {
        // calculate number of characters that will fit in a single line of the
        // usable area of the screen
        int usableWidth = getWidth () - getPaddingLeft () - getPaddingRight ();
        if (charWidthInPixels == 0) {
            Paint paint = getPaint ();
            charWidthInPixels = (int)(Math.ceil (paint.measureText ("M", 0, 1)));
            if (charWidthInPixels > 0) numVisibleChars = usableWidth / charWidthInPixels;
        }

        // calculate number of lines that will fit on the usable area of screen
        int usableHeight = getHeight () - getPaddingTop () - getPaddingBottom ();
        if (lineHeightInPixels == 0) {
            lineHeightInPixels = getLineHeight ();
            if (lineHeightInPixels > 0) numVisibleLines = usableHeight / lineHeightInPixels;
        }

        // make sure we got something for all that
        // it might be early on and we don't get rational stuff
        if ((usableWidth <= 0) || (charWidthInPixels <= 0) || (numVisibleChars <= 0) ||
                (usableHeight <= 0) || (lineHeightInPixels <= 0) || (numVisibleLines <= 0)) {
            charWidthInPixels  = 0;
            lineHeightInPixels = 0;
            numVisibleChars    = 0;
            numVisibleLines    = 0;
            return;
        }

        // maybe we need to inform host of a change in screen dimensions
        if ((lastsentptychrcols != numVisibleChars) ||
                (lastsentptychrrows != numVisibleLines) ||
                (lastsentptypixcols != usableWidth) ||
                (lastsentptypixrows != usableHeight)) {
            ChannelShell shellchan = session.getShellChannel ();
            if ((shellchan != null) && !frozen) {
                lastsentptychrcols = numVisibleChars;
                lastsentptychrrows = numVisibleLines;
                lastsentptypixcols = usableWidth;
                lastsentptypixrows = usableHeight;
                Log.d (TAG, "setPtySize ("  + lastsentptychrcols + ", " + lastsentptychrrows + ", " + lastsentptypixcols + ", " + lastsentptypixrows + ")");
                // doesn't throw anything, just discards any errors
                shellchan.setPtySize (lastsentptychrcols, lastsentptychrrows, lastsentptypixcols, lastsentptypixrows);
            }
        }

        // these arrays will be filled with the beginning and end of each visible line.
        // it will always have exactly numvisiblelines (possibly with blank lines on the top)
        // and the lines are no longer than numVisibleChars.
        if (visibleLineBegs.length != numVisibleLines) {
            visibleLineBegs = new int[numVisibleLines];
            visibleLineEnds = new int[numVisibleLines];
        }

        // get local copies of this stuff
        final char[] twt = screenTextBuffer.twt;  // theWholeText ring buffer of shell text from the host
        final int    twb = screenTextBuffer.twb;  // the base of all our indices
        final int    twm = screenTextBuffer.twm;  // mask for twt indices (twt.length()-1)
        final int    twu = screenTextBuffer.theWholeUsed;
        final int renderCursor = screenTextBuffer.renderCursor;

        // do initial mapping of text lines to visible lines.
        // vertical scrolling may be adjusted to make cursor line visible.
        // wrapped mode:
        //   lines are no wider than screen
        //   horizontal scrolling does not apply cuz there's nothing to scroll to
        // unwrapped mode:
        //   lines are full width of text lines and not horizontally scrolled
        boolean seencursor = (renderCursor < 0);  // pretend we have seen cursor if we aren't looking
        boolean showeols   = sshclient.getSettings ().show_eols.GetValue ();
        boolean wraplines  = sshclient.getSettings ().wrap_lines.GetValue ();
        int cursorvisline  = -1;                  // don't know what line cursor is in yet
        int nextlineend    = twu;                 // just past end of last line to be evaluated
        int precednewline  = -1;                  // don't know where newline that precedes nextlineend is
        int vislinenum     = numVisibleLines + screenTextBuffer.scrolledLinesDown;

        //  nextlineend > 0 : there is more text to look at
        //  vislinenum  > 0 : there are more visible lines to fill in
        //  !seencursor     : keep going anyway because we haven't seen cursor line yet
        while ((nextlineend > 0) && ((vislinenum > 0) || !seencursor)) {

            // nextlineend = just past what we want to display on this line
            //               never includes the newline char even if showeols mode
            int vislineend = nextlineend;

            // compute beginning of what to display on this line
            int vislinebeg;
            if (wraplines) {

                // if we don't know where the preceding newline is, find it
                if (precednewline < 0) {
                    for (precednewline = nextlineend; -- precednewline >= 0;) {
                        char ch = twt[(precednewline+twb)&twm];
                        if (ch == '\n') break;
                    }

                    // maybe extend this line to include newline on the end
                    if (showeols && (vislineend < twu)) {
                        vislineend ++;
                    }
                }

                // vislineend = just past what we want to display on this line
                //              might include newline if showeols mode
                // precednewline+1 = very beginning of line in theWholeText

                // figure out beginning of the visible line
                // - for 0..numVisibleChars of text, we get exactly one visible blank line
                // - for numVisibleChars+1 and up, the partial line if any is lower than full lines
                int numcharsleftinline = vislineend - precednewline - 1;
                int partiallinechars   = numcharsleftinline % numVisibleChars;
                if (partiallinechars > 0) {
                    vislinebeg = vislineend - partiallinechars;
                } else if (numcharsleftinline > 0) {
                    vislinebeg = vislineend - numVisibleChars;
                } else {
                    vislinebeg = vislineend;
                }

                // figure out end of line for next time through loop
                nextlineend = vislinebeg;
                if (nextlineend == precednewline + 1) {
                    nextlineend = precednewline;
                    precednewline = -1;
                }
            } else {

                // next loop's end of line is at preceding newline
                while (-- nextlineend >= 0) {
                    char ch = twt[(nextlineend+twb)&twm];
                    if (ch == '\n') break;
                }

                // this line's beginning is just after that newline
                vislinebeg = nextlineend + 1;

                // maybe extend line to include the newline as a character on the end
                if (showeols && (vislineend < twu)) {
                    vislineend ++;
                }
            }

            // maybe cursor is visible in this line
            boolean cursorinthisline = (renderCursor >= vislinebeg) && (renderCursor <= vislineend);

            // maybe we need to scroll text up to see cursor line
            if (cursorinthisline && (vislinenum > numVisibleLines)) {
                screenTextBuffer.scrolledLinesDown -= vislinenum - numVisibleLines;
                vislinenum = numVisibleLines;
            }

            // get index to store into
            //   i .ge. numVisibleLines: we are below the scrolled area so skip those lines
            //   i .lt. 0:               we need to scroll text down more to see the cursor
            int i = -- vislinenum;

            // store line limits in array
            if (i < numVisibleLines) {
                while (i < 0) i += numVisibleLines;
                visibleLineBegs[i] = vislinebeg;
                visibleLineEnds[i] = vislineend;
            }

            // remember which line cursor is in
            if (cursorinthisline) cursorvisline = i;

            // remember if cursor has been seen at all
            seencursor |= cursorinthisline;
        }

        if (vislinenum < 0) {

            // text needed to be scrolled down some more to see line with cursor
            // eg, if vislinenum == -1, text needed to be scrolled down one more line
            screenTextBuffer.scrolledLinesDown -= vislinenum;

            // find index in arrays where top line to be displayed is at
            // eg, if vislinenum == -1, top line is at index numVisibleLines - 1
            do vislinenum += numVisibleLines;
            while (vislinenum < 0);

            // rotate that index around to index 0 so it ends up at top
            if (vislinenum > 0) {
                int[] temp = new int[numVisibleLines];
                System.arraycopy (visibleLineBegs, vislinenum, temp, 0, numVisibleLines - vislinenum);
                System.arraycopy (visibleLineBegs, 0, temp, numVisibleLines - vislinenum, vislinenum);
                System.arraycopy (temp, 0, visibleLineBegs, 0, numVisibleLines);
                System.arraycopy (visibleLineEnds, vislinenum, temp, 0, numVisibleLines - vislinenum);
                System.arraycopy (visibleLineEnds, 0, temp, numVisibleLines - vislinenum, vislinenum);
                System.arraycopy (temp, 0, visibleLineEnds, 0, numVisibleLines);
            }

            // so theoretically cursor is in the top line now
            cursorvisline = 0;
        } else if (vislinenum > 0) {

            // if there are visible lines left at the top, fill them with null lines
            // use some number that can't possibly match a cursor, so any negative would work

            // but it is possible we have blank lines because text is scrolled down,
            // in which case we get rid of that much scrolling down and try again.
            if (screenTextBuffer.scrolledLinesDown > 0) {
                screenTextBuffer.scrolledLinesDown -= vislinenum;
                if (screenTextBuffer.scrolledLinesDown < 0) screenTextBuffer.scrolledLinesDown = 0;
                RenderText ();
                return;
            }

            if (vislinenum > numVisibleLines) vislinenum = numVisibleLines;
            while (-- vislinenum >= 0) {
                visibleLineBegs[vislinenum] = -999;
                visibleLineEnds[vislinenum] = -999;
            }
        }

        // apply horizontal scrolling and line length limit
        if (wraplines) {

            // there never is any horizontal scrolling if we are in wrap mode
            // and line length limits have already been applied
            screenTextBuffer.scrolledCharsLeft = 0;
        } else {

            // maybe adjust horizontal scrolling to make cursor visible
            if (cursorvisline >= 0) {

                // get index for beginning of full line
                int vislinebeg = visibleLineBegs[cursorvisline];

                // see how many chars over from beginning of line cursor is
                int cursorinline = renderCursor - vislinebeg;

                // if cursor is scrolled off to the left, scroll text right just enough
                // have one char to the left of cursor visible too if possible
                if (screenTextBuffer.scrolledCharsLeft >= cursorinline) {
                    screenTextBuffer.scrolledCharsLeft = cursorinline;
                    if (screenTextBuffer.scrolledCharsLeft > 0) -- screenTextBuffer.scrolledCharsLeft;
                }

                // if cursor is scrolled off to the right, scroll text left just enough
                if (screenTextBuffer.scrolledCharsLeft <= cursorinline - numVisibleChars) {
                    screenTextBuffer.scrolledCharsLeft = cursorinline - numVisibleChars + 1;
                }
            }

            // otherwise, maybe adjust horizontal scrolling to make *something* visible
            else {

                // get length of longest visible line
                int longestlen = 0;
                for (int i = 0; i < numVisibleLines; i ++) {
                    int linelen = visibleLineEnds[i] - visibleLineBegs[i];
                    if (longestlen < linelen) longestlen = linelen;
                }

                // if the end is scrolled off to the left, scroll text right just enough
                if ((longestlen > 0) && (screenTextBuffer.scrolledCharsLeft >= longestlen)) {
                    screenTextBuffer.scrolledCharsLeft = longestlen - 1;
                }
            }

            // apply horizontal scrolling and line length limit
            for (int i = 0; i < numVisibleLines; i ++) {

                // get indices for full length line (excluding the newline)
                int vislinebeg = visibleLineBegs[i];
                int vislineend = visibleLineEnds[i];

                // maybe text is scrolled over some
                vislinebeg += screenTextBuffer.scrolledCharsLeft;

                // never display more than visible width
                if (vislineend > vislinebeg + numVisibleChars) {
                    vislineend = vislinebeg + numVisibleChars;
                }

                // store possibly modified indices back
                visibleLineBegs[i] = vislinebeg;
                visibleLineEnds[i] = vislineend;
            }
        }
    }

    /**
     * Intercept any changes in font size so we can recompute pixel sizes.
     */
    @Override
    public void setTextSize (float size)
    {
        super.setTextSize (size);
        savedTextSize      = size;
        charWidthInPixels  = 0;
        lineHeightInPixels = 0;
        screenTextBuffer.needToRender = true;
        invalidate ();
    }

    /**
     * If the view dimensions change at all, re-render the text so we have the correct
     * number of characters and lines displayed to fill the screen.
     */
    @Override
    protected void onLayout (boolean changed, int left, int top, int right, int bottom)
    {
        super.onLayout (changed, left, top, right, bottom);
        if (changed) {
            charWidthInPixels  = 0;
            lineHeightInPixels = 0;
            screenTextBuffer.needToRender = true;
            invalidate ();
        }
    }

    /**
     * Panning (scrolling) the text.
     */
    @Override
    public boolean onTouchEvent (MotionEvent me)
    {
        panning.OnTouchEvent (me);
        return super.onTouchEvent (me);
    }

    public void PanCoastReceived ()
    {
        panning.PanCoast ();
    }

    private class STPanning extends PanAndZoom {
        private boolean coasting;
        private boolean isDown;
        private float startx, starty;
        private int startscrolledcharsleft;
        private int startscrolledlinesdown;

        private long  mousedownat_t, mousemoveat_t;
        private float mousedownat_x, mousemoveat_x;
        private float mousedownat_y, mousemoveat_y;

        public STPanning (Context ctx)
        {
            super (ctx);
        }

        // called when mouse pressed
        //  x,y = absolute mouse position
        public void MouseDown (float x, float y)
        {
            isDown = true;

            // remember where the user put his/her finger down originally
            startx = x;
            starty = y;
            startscrolledcharsleft = screenTextBuffer.scrolledCharsLeft;
            startscrolledlinesdown = screenTextBuffer.scrolledLinesDown;

            // remember where and when for coasting purposes
            mousedownat_t = 0;
            mousedownat_x = 0.0F;
            mousedownat_y = 0.0F;
            mousemoveat_t = SystemClock.uptimeMillis ();
            mousemoveat_x = x;
            mousemoveat_y = y;

            // if frozen, move selection cursor here
            if (frozen && (charWidthInPixels > 0) && (lineHeightInPixels > 0)) {
                int charno = (int)x / charWidthInPixels;
                int lineno = (int)y / lineHeightInPixels;
                if (lineno < 0) lineno = 0;
                if (lineno >= numVisibleLines) lineno = numVisibleLines - 1;
                if (charno < 0) charno = 0;
                selectCursor = charno + visibleLineBegs[lineno];
                if (selectCursor > visibleLineEnds[lineno]) selectCursor = visibleLineEnds[lineno];
                if (selectActive) selectEnd = selectCursor;
            }
        }

        // called when mouse released
        public void MouseUp ()
        {
            isDown = false;
            if (!coasting) {
                coasting = true;
                session.getScreendatahandler ().sendEmptyMessageDelayed (ScreenDataHandler.PANCOAST, 50);
            }
        }

        // called after a short delay after mouse released
        // keep the scrolling going at a decayed rate until it slows to a stop
        public void PanCoast ()
        {
            coasting = false;
            if (!isDown && (mousemoveat_t > mousedownat_t)) {
                float ratio  = 0.875F * (float)(SystemClock.uptimeMillis () - mousemoveat_t) / (float)(mousemoveat_t - mousedownat_t);
                float new_dx = (float)(mousemoveat_x - mousedownat_x) * ratio;
                float new_dy = (float)(mousemoveat_y - mousedownat_y) * ratio;
                float new_x  = mousemoveat_x + new_dx;
                float new_y  = mousemoveat_y + new_dy;
                Panning (new_x, new_y, new_dx, new_dy);
                if (new_dx * new_dx + new_dy * new_dy > 0.250F * ((float)charWidthInPixels * (float)charWidthInPixels + (float)lineHeightInPixels * (float)lineHeightInPixels)) {
                    coasting = true;
                    session.getScreendatahandler ().sendEmptyMessageDelayed (ScreenDataHandler.PANCOAST, 50);
                }
            }
        }

        // called when panning
        //  x,y = absolute mouse position
        //  dx,dy = delta position
        public void Panning (float x, float y, float dx, float dy)
        {
            mousedownat_t = mousemoveat_t;
            mousedownat_x = mousemoveat_x;
            mousedownat_y = mousemoveat_y;
            mousemoveat_t = SystemClock.uptimeMillis ();
            mousemoveat_x = x;
            mousemoveat_y = y;

            boolean render = false;
            if (charWidthInPixels > 0) {
                int deltacharsleft = (int)(startx - x) / charWidthInPixels;
                int scl = startscrolledcharsleft + deltacharsleft;
                if (scl < 0) scl = 0;
                if (screenTextBuffer.scrolledCharsLeft != scl) {
                    screenTextBuffer.scrolledCharsLeft = scl;
                    render = true;
                }
            }
            if (lineHeightInPixels > 0) {
                int deltalinesdown = (int)(y - starty) / lineHeightInPixels;
                int sld = startscrolledlinesdown + deltalinesdown;
                if (sld < 0) sld = 0;
                if (screenTextBuffer.scrolledLinesDown != sld) {
                    screenTextBuffer.scrolledLinesDown = sld;
                    render = true;
                }
            }
            if (render) {
                screenTextBuffer.needToRender = true;
                invalidate ();
            }
        }

        // called when scaling
        //  fx,fy = absolute position of center of scaling
        //  sf = delta scaling factor
        public void Scaling (float fx, float fy, float sf)
        {
            float ts = savedTextSize * sf;
            if (ts < Settings.TEXT_SIZE_MIN) ts = Settings.TEXT_SIZE_MIN;
            if (ts > Settings.TEXT_SIZE_MAX) ts = Settings.TEXT_SIZE_MAX;
            ScreenTextView.this.setTextSize (ts);
        }
    }

    /******************************\
     *  Outgoing data processing  *
    \******************************/

    // process menu and back keys, volume up/down
    //@Override
    //public boolean onKeyPreIme (int keyCode, KeyEvent ke)
    //{
    //    Log.d (TAG, "onKeyPreIme*: keyCode=" + keyCode + " ke=" + ((ke == null) ? "null" : ke.toString ()));
    //    return super.onKeyPreIme (keyCode, ke);
    //}

    // process enter and delete keys
    @Override
    public boolean dispatchKeyEvent (KeyEvent ke)
    {
        if (ke.getAction () == KeyEvent.ACTION_DOWN) {
            switch (ke.getKeyCode ()) {
                case KeyEvent.KEYCODE_ENTER: {
                    session.SendCharToHost (13);
                    return true;
                }
                case KeyEvent.KEYCODE_DEL: {
                    session.SendCharToHost (127);
                    return true;
                }
            }
        }
        return super.dispatchKeyEvent (ke);
    }

    /*
     * All this bologna to capture printable keys...
     *
     * TextWatcher sequence is:
     *   beforeTextChanged() once
     *   onTextChanged() twice!
     *   afterTextChanged() once
     */

    @Override // TextWatcher
    public void beforeTextChanged (CharSequence s, int start, int count, int after)
    { }

    @Override // TextWatcher
    public void onTextChanged (CharSequence s, int start, int before, int count)
    { }

    @Override // TextWatcher
    public void afterTextChanged (Editable s)
    {
        if (!readingkb) {
            readingkb = true;
            String str = s.toString ();
            s.replace (0, str.length (), "", 0, 0);
            for (char chr : str.toCharArray ()) {
                if (frozen) {
                    ProcessSelectionChar (chr);
                } else {
                    session.SendCharToHost (chr);
                }
            }
            readingkb = false;
        }
    }

    /**
     * Keyboard characters entered while frozen are used to navigate text selection.
     */
    private void ProcessSelectionChar (char ch)
    {
        switch (ch) {
            case 'B':
            case 'b': {  // begin
                selectBeg = selectEnd = selectCursor;
                selectActive = true;
                break;
            }
            case 'C':
            case 'c': {  // copy
                if (selectBeg != selectEnd) {
                    CopyToClipboard ();
                }
                break;
            }
            case 'I':
            case 'i': {  // up
                int lineno = screenTextBuffer.TextOffsetToLineNumber (selectCursor);
                int charno = screenTextBuffer.TextOffsetToCharNumber (selectCursor);
                int newlen = screenTextBuffer.TextLineLength (--lineno);
                if (charno > newlen) charno = newlen;
                selectCursor = screenTextBuffer.LineCharNumberToTextOffset (lineno, charno);
                break;
            }
            case 'J':
            case 'j': {  // left
                if (selectCursor > 0) -- selectCursor;
                break;
            }
            case 'K':
            case 'k': {  // down
                int lineno = screenTextBuffer.TextOffsetToLineNumber (selectCursor);
                int charno = screenTextBuffer.TextOffsetToCharNumber (selectCursor);
                int newlen = screenTextBuffer.TextLineLength (++lineno);
                if (charno > newlen) charno = newlen;
                selectCursor = screenTextBuffer.LineCharNumberToTextOffset (lineno, charno);
                break;
            }
            case 'L':
            case 'l': {  // right
                if (selectCursor < screenTextBuffer.theWholeUsed) selectCursor ++;
                break;
            }
            case 'R':
            case 'r': {  // reset
                selectActive = false;
                selectBeg = selectEnd = selectCursor;
                break;
            }
            default: {
                Log.w (TAG, "ignoring keyboard char <" + ch + "> while frozen");
                sshclient.MakeBeepSound ();
                break;
            }
        }
        if (selectActive) selectEnd = selectCursor;

        screenTextBuffer.needToRender = true;
        screenTextBuffer.renderCursor = selectCursor;
        invalidate ();
    }

    /**
     * Maybe send the selected character sequence to the clipboard.
     */
    private void CopyToClipboard ()
    {
        // get substring that was selected
        final char[] twt = screenTextBuffer.twt;
        final int    twb = screenTextBuffer.twb;
        final int    twm = screenTextBuffer.twm;
        int beg = (selectBeg <= selectEnd) ? selectBeg : selectEnd;
        int end = (selectEnd >= selectBeg) ? selectEnd : selectBeg;
        int len = end - beg;
        beg = (twb + beg) & twm;
        end = (twb + end - 1) & twm;
        final String subseq = (end >= beg) ?
                new String (twt, beg, len) :
                new String (twt, beg, twm + 1 - beg) + new String (twt, 0, ++ end);

        // start making an alert box
        AlertDialog.Builder ab = new AlertDialog.Builder (getContext ());
        ab.setTitle ("Copy to clipboard?");

        // its message is the selected string
        StringBuilder msg = new StringBuilder (len);
        if (len <= 50) {
            AppendSanitized (msg, subseq, 0, len);
        } else {
            AppendSanitized (msg, subseq, 0, 24);
            msg.append ("...");
            AppendSanitized (msg, subseq, len - 24, len);
        }
        ab.setMessage (msg.toString ());

        // Internal button does the copy then deselects string
        ab.setPositiveButton ("Internal", new DialogInterface.OnClickListener () {
            public void onClick (DialogInterface dialog, int whichButton) {
                sshclient.internalClipboard = subseq;
                selectActive = false;
                invalidate ();
            }
        });

        // External button does the copy then deselects string
        ab.setNeutralButton ("External", new DialogInterface.OnClickListener () {
            public void onClick (DialogInterface dialog, int whichButton) {
                ClipboardManager cbm = (ClipboardManager)sshclient.getSystemService (Context.CLIPBOARD_SERVICE);
                cbm.setText (subseq);
                selectActive = false;
                invalidate ();
            }
        });

        // Cancel button just leaves everything as is
        ab.setNegativeButton ("Cancel", null);

        // display the dialog box
        ab.show ();
    }

    /**
     * Make sure there are no newlines in the text for the message box.
     * That should be the only control character in the given text.
     */
    private void AppendSanitized (StringBuilder msg, CharSequence buf, int beg, int end)
    {
        for (int i = beg; i < end; i ++) {
            char c = buf.charAt (i);
            if (c == '\n') msg.append ("\\n");
            else if (c == '\\') msg.append ("\\\\");
            else msg.append (c);
        }
    }
}
