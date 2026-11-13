package com.k1af.ft8af.icom;
/**
 * Handle ICom audio stream, extends AudioUdp.
 * @author BGY70Z
 * @date 2023-08-26
 */

import android.util.Log;

import com.k1af.ft8af.GeneralVariables;
import com.k1af.ft8af.concurrent.SafeExecutor;

import java.net.DatagramPacket;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class IcomAudioUdp extends AudioUdp {
    private static final String TAG = "IcomAudioUdp";


    private final ExecutorService doTXThreadPool =Executors.newCachedThreadPool();

    /**
     * Guards against overlapping transmissions. Each {@link #sendTxAudioData} call
     * used to mutate the {@code audioData} field of a single shared
     * {@link DoTXAudioRunnable} and re-{@code execute()} that same instance on the
     * cached pool. A second TX (or Tune) firing before the first ~12.6&nbsp;s run
     * finished then ran the <em>same</em> Runnable on two pool threads at once —
     * stomping the shared PCM buffer mid-transmission and racing the unsynchronised
     * {@code innerSeq} increment, producing duplicated/garbled Icom-over-Wi-Fi audio
     * and a desynced packet sequence. We now snapshot the PCM per call and refuse a
     * new submission while one is in flight.
     *
     * <p>Package-private so unit tests can observe/reset the guard.
     */
    final AtomicBoolean transmitting = new AtomicBoolean(false);


    @Override
    public void sendTxAudioData(float[] audioData) {
        if (audioData==null) return;

        // Claim the transmit slot BEFORE converting. The conversion allocates a
        // short[] the length of a full ~12.6 s FT8 buffer and walks every sample;
        // doing it first meant an overlapping TX — the exact case this guard exists
        // for — paid that cost only to throw the result away.
        if (!transmitting.compareAndSet(false, true)) {
            // A transmission is already running on the pool. Starting another here
            // would run a concurrent DoTXAudioRunnable that interleaves PCM on the
            // shared socket and races innerSeq. Drop the overlapping request.
            Log.w(TAG, "sendTxAudioData: transmit already in progress, dropping overlapping request");
            return;
        }

        //Incoming audio is LPCM, 32-bit float, 12000Hz
        //iCOM audio format is LPCM 16-bit Int, 12000Hz
        //Need to convert from float to 16-bit int
        short[] temp;
        try {
            temp = convertForTransmit(audioData);
        } catch (RuntimeException | Error e) {
            // We hold the guard and never submitted, so release it here — a latched
            // guard would block every future transmission for the process lifetime.
            // (Deliberately not a finally around submitTransmit: that already
            // releases on a pool rejection, and re-clearing afterwards could clear a
            // guard a different thread had since claimed.)
            transmitting.set(false);
            throw e;
        }
        submitTransmit(temp);
    }

    /**
     * Instance seam over {@link #floatToPcm16} so tests can observe whether the
     * conversion ran at all (an overlapping request must be dropped before paying
     * for it) — same pattern as {@link #submitTransmit}.
     */
    short[] convertForTransmit(float[] audioData) {
        return floatToPcm16(audioData);
    }

    /**
     * Convert incoming LPCM 32-bit float samples ([-1.0, 1.0]) to LPCM 16-bit int.
     * Out-of-range samples are clamped before scaling, matching the original
     * inline conversion exactly.
     */
    static short[] floatToPcm16(float[] audioData) {
        short[] temp = new short[audioData.length];
        for (int i = 0; i < audioData.length; i++) {
            float x = audioData[i];
            if (x > 1.0)
                x = 1.0f;
            else if (x < -1.0)
                x = -1.0f;
            temp[i] = (short) (x * 32767.0);
        }
        return temp;
    }

    /**
     * Submit a fresh {@link DoTXAudioRunnable} for {@code pcm}. Each call gets its
     * own runnable holding an immutable snapshot, so there is no shared mutable
     * state between transmissions. If the pool rejects the task (shutdown race) the
     * transmit guard is released so a later TX can still start.
     *
     * <p>Package-private and overridable so unit tests can observe submissions
     * without a live socket.
     */
    void submitTransmit(short[] pcm) {
        if (!SafeExecutor.tryExecute(doTXThreadPool, new DoTXAudioRunnable(this, pcm))) {
            transmitting.set(false);
        }
    }

    private static class DoTXAudioRunnable implements Runnable{
        final IcomAudioUdp icomAudioUdp;
        final short[] audioData;//Incoming audio is LPCM 16-bit Int, 12000Hz

        public DoTXAudioRunnable(IcomAudioUdp icomAudioUdp, short[] audioData) {
            this.icomAudioUdp = icomAudioUdp;
            this.audioData = audioData;
        }

        @Override
        public void run() {
            try {
                if (audioData == null) return;

                final int partialLen = IComPacketTypes.TX_BUFFER_SIZE * 2;//Packet data length
                // Samples go out little-endian (low byte first), which is what the
                // helper below emits despite being named shortToBigEndian — see its
                // Javadoc in IComPacketTypes. Don't "fix" the byte order to match the
                // name; the wire format is little-endian.

                //byte[] data = new byte[audioData.length * 2 + partialLen * 4];//Extra silence padding: 20ms*2 = 80ms total before and after
                //Play silence first before audio; the for-i loop handles leading silence, the for-j loop handles trailing silence
                byte[] audioPacket = new byte[partialLen];
                for (int i = 0; i < (audioData.length / IComPacketTypes.TX_BUFFER_SIZE) + 8; i++) {//6 extra cycles: 3 before, 3 after
                    if (!icomAudioUdp.isPttOn) break;
                    long now = System.currentTimeMillis() - 1;//Get current time

                    icomAudioUdp.sendTrackedPacket(IComPacketTypes.AudioPacket.getTxAudioPacket(audioPacket
                            , (short) 0, icomAudioUdp.localId, icomAudioUdp.remoteId, icomAudioUdp.innerSeq));
                    icomAudioUdp.innerSeq++;

                    Arrays.fill(audioPacket, (byte) 0x00);
                    if (i >= 3) {//Let the first two empty packets be sent out
                        for (int j = 0; j < IComPacketTypes.TX_BUFFER_SIZE; j++) {
                            if ((i - 3) * IComPacketTypes.TX_BUFFER_SIZE + j < audioData.length) {
                                System.arraycopy(IComPacketTypes.shortToBigEndian((short)
                                                (audioData[(i - 3) * IComPacketTypes.TX_BUFFER_SIZE + j]
                                                        * GeneralVariables.volumePercent))//Multiply by volume ratio
                                        , 0, audioPacket, j * 2, 2);
                            }
                        }
                    }
                    while (icomAudioUdp.isPttOn) {
                        if (System.currentTimeMillis() - now >= 21) {//20ms per cycle
                            break;
                        }
                    }
                }
                Log.d(TAG, "run: Audio transmission complete!!");
            } finally {
                // Always release the guard, even if PTT dropped early or a send threw,
                // so the next TX cycle can start. (Previously the shared runnable also
                // wrongly left the pooled worker's interrupt flag set here.)
                icomAudioUdp.transmitting.set(false);
            }
        }

    }


    @Override
    public void onDataReceived(DatagramPacket packet, byte[] data) {
        super.onDataReceived(packet, data);
        //Received data is at 12000Hz sample rate
        if (!IComPacketTypes.AudioPacket.isAudioPacket(data)) return;
        byte[] audioData = IComPacketTypes.AudioPacket.getAudioData(data);
        if (onStreamEvents != null) {
            onStreamEvents.OnReceivedAudioData(audioData);
        }
    }
}
