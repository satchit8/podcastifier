(ns podcastifier.main
  (:gen-class)
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [incanter.core :as incanter]
            [incanter.charts :as charts])
  (:import [WavFile WavFile]
           [javax.sound.sampled AudioFormat AudioFormat$Encoding
            AudioInputStream AudioSystem Clip]))

;;; File management

(def file-number (atom 0))

(defn tempfile-name
  [n]
  (format "tempfile-%06d.wav" n))

(defn new-file
  []
  (tempfile-name (swap! file-number inc)))

(defn last-file
  []
  (tempfile-name @file-number))

;;; Time

(defn normalize-time
  "Returns a normalized time value given a time-like object, which is
  either a [h m s] tuple, a floating point number of seconds, or a
  string like \"02:15:22\""
  [t]
  (cond
   (vector? t)
   (let [[h m s] t

         s1 (mod s 60)
         m1 (+ m (int (Math/floor (/ s 60))))
         m2 (mod m1 60)
         h1 (+ h (int (Math/floor (/ m1 60))))]
     (+ (* 3600 h) (* 60 m) s))

   (number? t) t

   (string? t)
   (let [[h m s] (str/split t #":")]
     (normalize-time [(Long/parseLong h)
                      (Long/parseLong m)
                      (Double/parseDouble s)]))

   :else
   (throw (ex-info "Unsupported timelike"
                   {:reason :unsupported-timelike
                    :t t}))))

(defn time->str
  "Return a string like \"01:02:03\" given a timelike value."
  [t]
  (let [s (normalize-time t)
        h (int (/ s 3600))
        s* (- s (* h 3600))
        m (int (/ s* 60))
        s** (- s* (* m 60))]
    (format "%02d:%02d:%s%2.3f" h m (if (< s** 10) "0" "") s**)))

(defn add-time
  "Sums `times`"
  [& times]
  (reduce + (map normalize-time times)))

(defn subtract-time
  "Subtracts time `t2` from `t1`"
  [t1 t2]
  (- (normalize-time t1) (normalize-time t2)))

;;; External process integration

(defn sh
  "Invokes the specified command with `args`"
  [command & args]
  (let [{:keys [exit out err]} (apply sh/sh command (map str args))]
    (when-not (zero? exit)
      (throw (ex-info (str "Invocation of " command " failed")
                      {:reason :command-invocation-failure
                       :args args
                       :command command
                       :exit exit
                       :out out
                       :err err})))))
(defn sox
  "Returns an invocation of sox with the specified arguments"
  [& args]
  (apply sh "sox" args))

;;; Sounds

(defprotocol Sound
  "Defines a notional sound."
  (duration [sound] "Returns the duration of the sound in seconds.")
  (sample [sound t] "A function of one argument (a time in seconds) that returns a vector
  of floating point numbers representing the amplitidue of each
  channel at that point in time."))

;; A simple in-memory sound generated by the `sampler` function
(defrecord BasicSound [duration sampler]
  Sound
  (duration [this] (:duration this))
  (sample [this t]   (if (<= 0 t (:duration this))
                       ((:sampler this) t)
                       ;; Otherwise, a vector containing as many zeros
                       ;; as there channels, which we infer from the
                       ;; sample at t=0.0
                       (vec (repeat (count ((:sampler this) 0.0)) 0.0)))))

(defn null-sound
  "Returns a zero-duration sound with one channel."
  []
  (->BasicSound 0.0 (constantly [0.0])))

(defn channels
  "Return the number of channels in `sound`."
  [sound]
  (count (sample sound 0.0)))

(defn fade-value
  "Returns the amount to fade by given a time and a `controls` spec
  like the one given to `fade`, except where the time values must have
  been normalized first."
  [t controls]
  (let [[last-v last-t] (last controls)]
    (if (<= last-t t)
      last-v
      (let [c* (apply vector [1.0 0] controls)
            control-pairs (partition 2 1 c*)

            [start-v start-t end-v end-t]
            (some (fn [[[start-v start-t] [end-v end-t]]]
                    (when (<= start-t t end-t)
                      [start-v start-t end-v end-t]))
                  control-pairs)]
        (if (= end-t start-t)
          start-v
          (let [delta-v (- (double end-v) start-v)
                delta-t (- (double end-t) start-t)
                v       (+ start-v
                           (* delta-v (/ (- (double t) start-t) delta-t)))]
            v))))))

(defn sinusoid
  "Returns a single-channel sound of `duration` and `frequency`."
  [duration ^double frequency]
  (->BasicSound duration
                (fn [^double t]
                  [(Math/sin (* t frequency 2.0 Math/PI))])))

;;; File-based Sound

(defn- advance-frames
  "Reads and discards `n` frames from AudioInputStream `ais`. Returns
  the number of frames actually read."
  [^AudioInputStream ais n]
  (let [bytes-per-frame (-> ais .getFormat .getFrameSize)
        discard-frame-max 1000
        discard-buffer-bytes (* bytes-per-frame discard-frame-max)
        discard-buffer (byte-array discard-buffer-bytes)]
    (loop [total-frames-read 0]
      (let [frames-left-to-read (- n total-frames-read)]
        (if (pos? frames-left-to-read)
          (let [frames-to-read (min discard-frame-max frames-left-to-read)
                bytes-to-read (* bytes-per-frame frames-to-read)
                bytes-read (.read ais discard-buffer (int 0) (int bytes-to-read))
                frames-read (/ bytes-read bytes-per-frame)]
            (if (neg? frames-read)
              total-frames-read
              (recur (+ total-frames-read frames-read))))
          total-frames-read)))))

(defn read-sound
  "Returns a Sound for the file at `path`."
  [path]
  (let [file                   (io/file path)
        in                     (atom (AudioSystem/getAudioInputStream file))
        base-format            (.getFormat @in)
        base-file-format       (AudioSystem/getAudioFileFormat file)
        base-file-properties   (.properties base-file-format)
        base-file-duration     (get base-file-properties "duration")
        bits-per-sample        16
        bytes-per-sample       (/ bits-per-sample 8)
        channels               (.getChannels base-format)
        bytes-per-frame        (* bytes-per-sample channels)
        frames-per-second      (.getSampleRate base-format)
        decoded-format         (AudioFormat. AudioFormat$Encoding/PCM_SIGNED
                                             frames-per-second
                                             bits-per-sample
                                             channels
                                             (* bytes-per-sample channels)
                                             frames-per-second
                                             true)
        din                    (atom (AudioSystem/getAudioInputStream decoded-format @in))
        decoded-length-seconds (if base-file-duration
                                 (/ base-file-duration 1000000.0)
                                 (/ (.getFrameLength @din) frames-per-second))
        buffer-seconds         10
        buffer                 (byte-array (* frames-per-second
                                              buffer-seconds
                                              channels
                                              bytes-per-sample))
        starting-buffer-pos    [nil nil]
        buffer-pos             (atom starting-buffer-pos)
        bb                     (java.nio.ByteBuffer/allocate bytes-per-frame)]
    (reify
      Sound
      (duration [s] decoded-length-seconds)
      (sample [s t]
        (if-not (<= 0.0 t decoded-length-seconds)
          (vec (repeat channels 0.0))
          (let [frame-at-t (-> t (* frames-per-second) long)]
            ;;(println "buffer-pos" @buffer-pos)

            ;; Desired frame is before current buffer. Reset everything
            ;; to the start state
            (let [effective-start-of-buffer (or (first @buffer-pos) -1)]
             (when (< frame-at-t effective-start-of-buffer)
               ;;(println "rewinding")
               (.close @din)
               (.close @in)
               (reset! in (AudioSystem/getAudioInputStream (io/file path)))
               (reset! din (AudioSystem/getAudioInputStream decoded-format @in))
               (reset! buffer-pos starting-buffer-pos)))

            ;; Desired position is past the end of the buffered region.
            ;; Update buffer to include it.
            (let [effective-end-of-buffer (or (second @buffer-pos) -1)]
             (when (< effective-end-of-buffer frame-at-t)
               (let [frames-to-advance (- frame-at-t effective-end-of-buffer 1)]
                 ;; We can't skip, because there's state built up during .read
                 ;; (println "Advancing to frame" frame-at-t
                 ;;          "by going forward" frames-to-advance
                 ;;          "frames")
                 (let [frames-advanced (advance-frames @din frames-to-advance)]
                   (if (= frames-to-advance frames-advanced)
                     (let [bytes-read (.read @din buffer)]
                       (if (pos? bytes-read)
                         (let [frames-read (/ bytes-read bytes-per-frame)]
                           (reset! buffer-pos [frame-at-t (+ frame-at-t frames-read)]))
                         (reset! buffer-pos starting-buffer-pos)))
                     (reset! buffer-pos starting-buffer-pos))))))

            ;; Now we're either positioned or the requested position
            ;; cannot be found
            (let [[buffer-start-pos buffer-end-pos] @buffer-pos]
              (if buffer-end-pos
                (let [buffer-frame-offset (- frame-at-t buffer-start-pos)
                      buffer-byte-offset (* buffer-frame-offset bytes-per-frame)]
                  (.position bb 0)
                  (.put bb buffer buffer-byte-offset bytes-per-frame)
                  (.position bb 0)
                  ;; TODO: We're hardcoded to .getShort here, but the
                  ;; bits-per-frame is a parameter. Should probably have
                  ;; something that knows how to read from a ByteBuffer
                  ;; given a number of bits.
                  (vec (repeatedly channels #(/ (double (.getShort bb)) (inc Short/MAX_VALUE)))))
                (vec (repeat channels 0)))))))

      java.io.Closeable
      (close [this]
        (.close @din)
        (.close @in)))))

;;; Sound manipulation

(defn ->stereo
  "Turns a sound into a two-channel sound. Currently works only on
  one- and two-channel inputs."
  [s]
  (case (channels s)
    1 (->BasicSound (duration s) (fn [^double t] (let [x (sample s t)] [x x])))
    2 s
    (throw (ex-info "Can't stereoize sounds with other than one or two channels"
                    {:reason :cant-stereoize-channels :s s}))))

(defn fade
  "Given `s` returns a new sound whose gain has been adjusted
  smoothly between the [gain time] points in `controls`, where gain is
  between 0.0 and 1.0 (inclusive) and time is as described below.
  There are implicit control points at the beginning of the audio with
  a gain of 1.0 and at the end of the audio with the last gain
  specified.

  Times can be specified as a number, interpreted as seconds, as a
  vector of numbers, interpreted as [hours minutes seconds], or as a
  string, which must be of the form \"hh:mm:ss.sss\".

  Example: (fade \"foo.wav\" \"bar.wav\" [[0 0] [1 14.5]]) would fade
  the audio in smoothly until 14.5 seconds in, then stay at full
  volume for the resnt of the file.

  Example: (fade \"foo.wav\" \"bar.wav\" [[1.0 0]
                                          [0.2 12.0]
                                          [0.2 \"00:01:30\"]
                                          [0 \"00:01:45\"]])

  would start at full volume, fade down to 20% volume at 12 seconds,
  stay there until 90 seconds in, and then fade out completely by 15
l  seconds later."
  [s controls]
  (let [normalized-controls (mapv (fn [[v t]] [v (normalize-time t)]) controls)]
    (->BasicSound (duration s)
                  (fn [t]
                    (mapv #(* (fade-value t controls) %)
                          (sample s t))))))

(defn pan
  "Takes a two-channel sound and mixes the channels together by
  `amount`, a float on the range [0.0, 1.0]. The ususal use is to take
  a sound with separate left and right channels and combine them so
  each appears closer to stereo center. An `amount` of 0.0 would leave
  both channels unchanged, 0.5 would result in both channels being the
  same, and 1.0 would switch the channels."
  [s amount]
  {:pre [(= 2 (channels s))]}
  (let [{source-duration :duration
         source-sampler :sampler} s
         amount-complement (- 1.0 amount)]
    (->BasicSound source-duration
                  (fn [t]
                    (let [[a b] (source-sampler t)]
                      [(+ (* a amount-complement)
                          (* b amount))
                       (+ (* a amount)
                          (* b amount-complement))])))))

(defn trim
  "Truncates `s` to the region between `start` and `end`."
  [s start end]
  (let [start* (-> start normalize-time)
        end* (-> end normalize-time)]
    (->BasicSound (- end* start*)
                  (fn [^double t] (sample s (+ t start))))))

(defn fade-in
  "Fades `s` linearly from zero at the beginning to full volume at
  `duration`."
  [s duration]
  (fade s [[0 0] [1.0 duration]]))

(defn fade-out
  "Fades the s to zero for the last `duration`."
  [s duration]
  (let [end (:duration s)]
    (fade s [[1.0 (- end duration)] [0.0 end]])))

(defn mix
  "Mixes files `s1` and `s2`."
  [s1 s2]
  (->BasicSound (max (duration s1) (duration s2))
                (fn [t]
                  (mapv + (sample s1 t) (sample s2 t)))))

(defn timeshift
  "Inserts `amount` seconds of silence at the beginning of `s`"
  [s amount]
  (let [channels (channels s)]
   (->BasicSound (+ (duration s) amount)
                 (fn [^double t] (if (< t amount)
                                   (vec (repeat channels 0.0))
                                   (sample s (- t amount)))))))

(defn- input-for-t
  "Given a seq of [start-time end-time sound] tuples, return the tuple
  with a `t` that falls between the `start-time` and `end-time`.
  Assumes the tuples are sorted in time order with no overlap."
  [input-descriptions t]
  (or (first (filter (fn [[start end input]] (<= start t end)) input-descriptions))
      (null-sound)))

(defn append
  "Concatenates inputs together."
  [& inputs]
  (let [end-times (->> inputs (map duration) (reductions +))
        start-times (concat [0.0] end-times)
        input-descriptions (map vector start-times end-times inputs)]
    (->BasicSound (last end-times)
                  (fn [^double t]
                    (let [[start end input] (input-for-t input-descriptions t)]
                      (sample input (- t start)))))))

(defn silence
  "Creates a single-channel sound that is `duration` long but silent."
  [duration]
  (->BasicSound duration (constantly [0.0])))

;;; Playback

(defn short-sample
  "Takes a floating-point number f in the range [-1.0, 1.0] and scales
  it to the range of a 16-bit integer. Clamps any overflows."
  [f]
  (let [f* (-> f (min 1.0) (max -1.0))]
    (short (* Short/MAX_VALUE f))))

;; TODO: There some crackle in the playback. Figure out why and kill
;; it. Maybe oversample?
(defn play
  "Plays `sound`. May return before sound has finished playing."
  [s]
  (let [sample-rate  44100
        channels     (channels s)
        sdl          (AudioSystem/getSourceDataLine (AudioFormat. sample-rate
                                                                  16
                                                                  channels
                                                                  true
                                                                  true))
        buffer-bytes (* sample-rate channels) ;; Half-second
        bb           (java.nio.ByteBuffer/allocate buffer-bytes)
        total-bytes  (-> s duration (* sample-rate) long (* channels 2))
        byte->t      (fn [n] (-> n double (/ sample-rate channels 2)))]
    (.open sdl)
    (loop [current-byte 0]
      (when (< current-byte total-bytes)
        (let [bytes-remaining (- total-bytes current-byte)
              bytes-to-write (min bytes-remaining buffer-bytes)]
          (.position bb 0)
          (doseq [i (range 0 bytes-to-write (* 2 channels))]
            (let [t  (byte->t (+ current-byte i))
                  frame (sample s t)]
              ;;(println t frame)
              (doseq [samp frame]
                (.putShort bb (short-sample samp)))))
          (let [bytes-written (.write sdl (.array bb) 0 bytes-to-write)]
            (.start sdl)                ; Repeated calls are harmless
            (recur (+ current-byte bytes-written))))))))

;;; Visualization

(defn visualize
  "Visualizes `s` by plottig it on a graph."
  ([s] (visualize s 0))
  ([s channel]
     (let [duration (duration s)]
       ;; TODO: Maybe use a function that shows power in a window
       ;; around time t rather than just the sample
       (incanter/view (charts/function-plot #(nth (sample s %) channel 0.0)
                                            0.0
                                            duration
                                            :step-size (/ duration 4000.0))))))

(defn -main
  "Entry point for the application"
  [config-path]
  #_(let [config (-> config-path io/reader (java.io.PushbackReader.) edn/read)]
    (let [pan-f (if (-> config :voices :pan?) pan identity)
          voice (-> config :voices :both
                    pan-f
                    (trim
                     (subtract-time
                      (-> config :voices :start)
                      (-> config :voices :fade-in))
                     (-> config :voices :end))
                    (fade-in
                     (-> config :voices :fade-in)))
          voice-rate (rate voice)
          intro-soft-start (add-time (-> config :music :intro :full-volume-length)
                                     (-> config :voices :fade-in))
          intro-soft-end (add-time intro-soft-start
                                   (subtract-time (-> config :voices :intro-music-fade)
                                                  (-> config :voices :start)))
          intro-end (add-time intro-soft-end (-> config :music :intro :fade-out))
          intro (-> config :music :intro :file
                    (to-wav voice-rate)
                    (fade
                     [[1.0 (-> config :music :intro :full-volume-length)]
                      [(-> config :music :intro :fade-amount) intro-soft-start]
                      [(-> config :music :intro :fade-amount) intro-soft-end]
                      [0.0 intro-end]])
                    (trim 0.0 intro-end))
          outro-fade-up-start (subtract-time (-> config :voices :end)
                                             (-> config :voices :outro-music-start))
          outro-fade-up-end (add-time outro-fade-up-start
                                      (-> config :music :outro :fade-up))
          outro-fade-out-start (add-time outro-fade-up-end
                                         (-> config :music :outro :full-volume-length))
          outro-fade-out-end (add-time outro-fade-out-start
                                       (-> config :music :outro :fade-out))
          outro (-> config :music :outro :file
                    (to-wav voice-rate)
                    (fade
                     [[(-> config :music :outro :fade-amount) 0]
                      [(-> config :music :outro :fade-amount) outro-fade-up-start]
                      [1.0 outro-fade-up-end]
                      [1.0 outro-fade-out-start]
                      [0.0 outro-fade-out-end]])
                    (match-sample-rate voice)
                    (trim 0.0 outro-fade-out-end))
          outro-music-start (-> (-> config :voices :outro-music-start)
                                (subtract-time (-> config :voices :start))
                                (add-time (-> config :voices :fade-in)))
          voice-with-outro (mix voice outro outro-music-start)
          voice-with-intro (mix intro voice-with-outro
                                (-> config :music :intro :full-volume-length))
          bumper-length (length (-> config :bumper))
          bumper-music-start (-> config :music :bumper :start-at)
          bumper-music-fade-start (add-time bumper-music-start bumper-length)
          bumper-music-end (add-time bumper-music-fade-start
                                     (-> config :music :bumper :fade-out))
          bumper-fade (-> config :music :bumper :fade-amount)
          bumper-music (-> config :music :bumper :file
                           (to-wav voice-rate)
                           (trim bumper-music-start bumper-music-end)
                           (fade [[bumper-fade 0.0]
                                  [bumper-fade bumper-length]
                                  [0.0 (add-time bumper-length
                                                 (-> config :music :bumper :fade-out))]]))
          bumper-with-music (-> config :bumper
                                (to-wav voice-rate)
                                (mix bumper-music 0.0))
          final (append bumper-with-music
                        (silence voice 1)
                        (to-wav (-> config :bloops :bumper) voice-rate)
                        (silence voice 3)
                        voice-with-intro
                        (silence voice 2)
                        (to-wav (-> config :bloops :end) voice-rate))]
      {:bumper-with-music bumper-with-music
       :voice-with-intro voice-with-intro
       :final final})))