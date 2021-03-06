/**
 * This thread copies characters from the remote host and
 * queues them to be displayed in the scrolled text window.
 * It lives on as part of JSessionService even when the GUI
 * is detached, so as to keep the connection alive and pass
 * on data to ScreenTextBuffer.
 *
 * JSessionService holds these in memory.
 *
 * These in turn hold the jsession (TCP connection) in memory.
 *
 * These also hold the corresponding ScreenTextBuffer in memory
 * so there is someplace to put incoming host data.
 *
 * When the GUI is detached, it is detached from ScreenTextBuffer,
 * so it should be garbage collected.
 *
 * THe only other thing these should point to are the detstate
 * hash maps which just have primitives in them describing the
 * GUI state (high level) so it can be restored.
 *
 * Note that this thread doesn't actually have to be running to
 * fulfill its function of keeping the jsession alive, in the
 * case where it is just being used for sftp or tunnelling.  It
 * will be running only if the connection is being used for shell
 * access.
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


import android.util.Log;

import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.Session;

import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;

public class ScreenDataThread extends Thread {
    public static final String TAG = "SshClient";

    //TODO move this to JSessionService
    public interface GUIDetach {
        public void guiDetach ();
    }

    public ChannelShell channel;
    public OutputStreamWriter output;
    public ScreenTextBuffer screenTextBuffer;
    public Session jsession;

    private boolean enabled;
    private boolean started;
    private volatile boolean terminated;

    // Not used by ScreenDataThread itself but needed
    // to re-attach when restarting.
    // Only holds primitives like ints and strings (or
    // other hashmaps of ints and strings) so when the
    // GUI detaches, the GUI components can be garbage
    // collected.  Can also hold objects that implement
    // GUIDetach as it will be called on detach so they
    // can remove any GUI references at that time.
    //TODO move this to JSessionService
    public HashMap<String,Object> detstate = new HashMap<String,Object> ();

    /**
     * If not already in shell mode, start it going.
     * Called in GUI thread.
     */
    public void startshellmode ()
    {
        synchronized (this) {
            enabled = true;
            if (!started) start ();
                else notifyAll ();
            started = true;
        }
    }

    /**
     * Tell thread to exit and wait for it.
     * Called in GUI thread.
     */
    public void terminate ()
    {
        if (started) {
            // tell thread to terminate asap
            terminated = true;
            synchronized (this) {
                while (true) {

                    // wake thread so it will see terminate flag
                    // in case it is in its wait() call
                    notifyAll ();

                    // disconnect any open channel
                    // should be sufficient to get its input.read() to terminate
                    try { output.close       (); } catch (Exception e) { }
                    try { channel.disconnect (); } catch (Exception e) { }
                    output  = null;
                    channel = null;

                    // we are done if thread has seen terminate flag
                    if (!enabled) break;

                    // wait for it to see the terminate flag
                    try { wait (); } catch (InterruptedException ie) { }
                }
            }

            // wait for thread to actually exit
            while (true) try {
                join ();
                break;
            } catch (InterruptedException ie) { }
        }
    }

    /**
     * GUI is detaching.
     * Shed pointers to anything we don't want to keep while detached.
     *
     * Things get re-attached in MySession constructor.
     */
    //TODO move this to JSessionService
    public void detach ()
    {
        // this is how stuff gets from the ScreenTextBuffer to the GUI
        screenTextBuffer.SetChangeNotification (null);

        // release any other GUI stuff
        checkForGUIDetaches (detstate, "");
    }

    //TODO move this to JSessionService
    private static void checkForGUIDetaches (HashMap map, String indent)
    {
        for (Object key : map.keySet ()) {
            Object obj = map.get (key);
            if (obj instanceof HashMap) {
                Log.d (TAG, "ScreenDataThread.detach: " + indent + key.toString () + "=(" + obj.getClass ().getSimpleName () + ")...");
                checkForGUIDetaches ((HashMap)obj, indent + "  ");
            } else {
                Log.d (TAG, "ScreenDataThread.detach: " + indent + key.toString () + "=(" + obj.getClass ().getSimpleName () + ")" + obj.toString ());
                if (obj instanceof GUIDetach) {
                    Log.d (TAG, "ScreenDataThread.detach: " + indent + "- GUIDetach");
                    ((GUIDetach)obj).guiDetach ();
                }
            }
        }
    }

    /**
     * Shell mode thread that reads data from the host and posts it to the ScreenTextBuffer,
     * whether or not there is a GUI to look at it.
     */
    @Override
    public void run ()
    {
        do {
            InputStreamReader input = null;

            try {
                // open shell channel
                screenTextBuffer.ScreenMsg ("\n[" + hhmmssNow () + "] opening shell\n");
                channel = (ChannelShell)jsession.openChannel ("shell");
                screenTextBuffer.ScreenMsg ("...creating streams\n");
                input   = new InputStreamReader  (channel.getInputStream  ());
                output  = new OutputStreamWriter (channel.getOutputStream ());
                screenTextBuffer.ScreenMsg ("...setting pty type 'dumb'\n");
                channel.setPtyType ("dumb");
                screenTextBuffer.ScreenMsg ("...connecting shell\n");
                channel.connect ();
                screenTextBuffer.ScreenMsg ("...connected\n");

                // read screen data from host and send it to screen
                char[] buf = new char[4096];
                int len;
                while (!terminated && ((len = input.read (buf, 0, buf.length)) >= 0)) {
                    screenTextBuffer.Incoming (buf, 0, len);
                }
            } catch (Exception e) {
                Log.w (TAG, "receive error", e);
                screenTextBuffer.ScreenMsg ("\n[" + hhmmssNow () + "] receive error: " + SshClient.GetExMsg (e) + "\n");
            }

            // end of shell channel, display message
            if (!terminated) {
                screenTextBuffer.ScreenMsg ("\n[" + hhmmssNow () + "] shell closed\n");
                screenTextBuffer.ScreenMsg (
                    jsession.isConnected () ?
                            "  menu/more/shell to re-open\n  menu/more/disconnect or EXIT to disconnect\n" :
                            "  menu/more/disconnect then reconnect to re-open\n"
                );
            }

            // get everything closed up
            // then wait to be re-enabled or terminated
            synchronized (this) {
                try { input.close        (); } catch (Exception e) { }
                try { output.close       (); } catch (Exception e) { }
                try { channel.disconnect (); } catch (Exception e) { }
                output  = null;
                channel = null;
                enabled = false;
                notifyAll ();

                while (!enabled && !terminated) {
                    try { wait (); } catch (InterruptedException ie) { }
                }
            }
        } while (!terminated);
    }

    public static String hhmmssNow ()
    {
        return new SimpleDateFormat ("HH:mm:ss").format(new Date ());
    }
}
