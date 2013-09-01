(ns cellular.cellular
#+clj   (:require [clojure.core.async :refer [<! >! chan go]])
#+cljs  (:require [cljs.core.async :refer [>! <! chan]])
#+cljs  (:require-macros [cljs.core.async.macros :refer [go alt!]]))

(defn initializer
  "Return a function that initializes a single data cell"
  [n initial-values]
  (let [{:keys [north-boundary south-boundary east-boundary west-boundary interior]} initial-values]
    (fn init-cell [i j]
      (cond
        (zero? i) north-boundary
        (= (inc n) i) south-boundary
        (= (inc n) j) east-boundary
        (zero? j) west-boundary
        :else interior))))

(defn newgrid-row
  [m init-cell i0 j0 i]
  (let [row (atom [])]
    (doseq [j (range (+ 2 m))]
      (swap! row conj (init-cell (+ i0 i) (+ j0 j))))
    row))
      
(defn newgrid
  "Return a function that initializes a subgrid
of size (m + 2) X (m + 2).
The subgrids overlap on all four sides."
  [m init-cell]
  (fn init-subgrid [node-i node-j]
    (let [i0 (* (dec node-i) m)
          j0 (* (dec node-j) m)
          grid (atom [])]
      (doseq [i (range (+ 2 m))]
        (swap! grid conj @(newgrid-row m init-cell i0 j0 i)))
      grid)))
        
(defn phase1-step
  [subgrid-atom k params]
  (let [{:keys [q m node-i node-j neighbors]} params
        {:keys [north south east west]} neighbors
        done (chan)
        out (chan)]
    (go
      (when (> node-i 1) (swap! subgrid-atom assoc-in [0 k] (<! north)))
      (>! done :done))
    (go
      (when (< node-i q) (>! south (get-in @subgrid-atom [m k])))
      (>! done :done))
    (go
      (when (< node-j q) (>! east (get-in @subgrid-atom [k m])))
      (>! done :done))
    (go
      (when (> node-j 1) (swap! subgrid-atom assoc-in [k 0] (<! west)))
      (>! done :done))
    (go
      (dotimes [_ 4]
        (<! done))
      (>! out subgrid-atom))
    out))

(defn phase2-step
  [subgrid-atom k params]
  (let [{:keys [q m node-i node-j neighbors]} params
        {:keys [north south east west]} neighbors
        done (chan)
        out (chan)]
    (go
      (when (> node-i 1) (>! north (get-in @subgrid-atom [1 k])))
      (>! done :done))
    (go
      (when (< node-i q) (swap! subgrid-atom assoc-in [(inc m) k] (<! south)))
      (>! done :done))
    (go
      (when (< node-j q) (swap! subgrid-atom assoc-in [k (inc m)] (<! east)))
      (>! done :done))
    (go
      (when (> node-j 1) (>! west (get-in @subgrid-atom [k 1])))
      (>! done :done))
    (go
      (dotimes [_ 4]
        (<! done))
      (>! out subgrid-atom))
    out))

(defn exchange-phase
  [first last step subgrid-atom params]
  (let [out (chan)]
    (go
      (let [new-subgrid-atom (loop [k first
                                    subgrid-atom subgrid-atom]
                               (if (> k last)
                                 subgrid-atom
                                 (recur (+ 2 k) (<! (step subgrid-atom k params)))))]
        (>! out new-subgrid-atom)))
    out))

(defn exchange-phase1
  [subgrid-atom params]
  (let [{:keys [m parity]} params
        first (- 2 parity)
        last (- m parity)
        step phase1-step]
    (exchange-phase first last step subgrid-atom params)))

(defn exchange-phase2
  [subgrid-atom params]
  (let [{:keys [m parity]} params
        first (inc parity)
        last (dec (+ m parity))
        step phase2-step]
    (exchange-phase first last step subgrid-atom params)))

(defn exchange
  [subgrid-atom params]
  (let [out (chan)]
    (go
      (let [subgrid-atom (<! (exchange-phase1 subgrid-atom params))
            subgrid-atom (<! (exchange-phase2 subgrid-atom params))]
        (>! out subgrid-atom)))
    out))

(defn relaxation-phase
  [params]
  (let [{:keys [transition m]} params]
    (fn relax-phase [subgrid-atom parity]
      (let [assoc-next-states-in (fn [subgrid-atom]
                                   (doseq [i (range 1 (inc m))]
                                     (let [k (mod (+ i parity) 2)
                                           last (- m k)]
                                       (doseq [j (range (- 2 k) (inc last) 2)]
                                         (swap! subgrid-atom assoc-in [i j] (transition @subgrid-atom i j)))))
                                   subgrid-atom)]
        (let [params (assoc params :parity (- 1 parity))
              out (chan)]
          (go
            (let [subgrid-atom (<! (exchange subgrid-atom params))
                  subgrid-atom (assoc-next-states-in subgrid-atom)]
              (>! out subgrid-atom)))
          out)))))
      
(defn relaxation-step
  [subgrid-atom params]
  (let [out (chan)]
    (go
      (let [relax-phase (relaxation-phase params)
            subgrid-atom (<! (relax-phase subgrid-atom 0))
            subgrid-atom (<! (relax-phase subgrid-atom 1))]
        (>! out subgrid-atom)))
    out))

(defn relaxation
  [q m transition]
  (fn relax [subgrid-atom params]
    (let [{:keys [steps]} params
          out (chan)]
      (go
        (let [subgrid-atom (loop [step 0
                                  subgrid-atom subgrid-atom]
                             (if (= step steps)
                               subgrid-atom
                               (recur (inc step)
                                      (<! (relaxation-step subgrid-atom (assoc params :q q :m m :transition transition))))))]
          (>! out subgrid-atom)))
      out)))

(def RELAXATION-STEPS-PER-OUTPUT 1)
  
(defn node
  [init-subgrid relax out]
  (fn init-node [node-i node-j neighbors]
    (go
      (loop [subgrid-atom (init-subgrid node-i node-j)]
        (>! out {:subgrid @subgrid-atom :node-i node-i :node-j node-j})
        (recur (<! (relax subgrid-atom {:node-i node-i
                                        :node-j node-j
                                        :neighbors neighbors
                                        :steps RELAXATION-STEPS-PER-OUTPUT})))))))

(defn master
  [q m n]
  (let [start-time #+clj (System/nanoTime) #+cljs (.getTime (js/Date.))
        in (chan)
        out (chan)
        grid (atom (vec (take n (repeat (vec (take n (repeat nil)))))))]
    (go (while true
          (doseq [_ (range (* q q))]
            (let [{:keys [subgrid node-i node-j]} (<! in)
                  i0 (* (dec node-i) m)
                  j0 (* (dec node-j) m)]
              (doseq [i (range m)
                      j (range m)]
                (swap! grid assoc-in [(+ i0 i) (+ j0 j)] (get-in subgrid [(inc i) (inc j)])))))
          (let [elapsed-ms #+clj (long (/ (- (System/nanoTime) start-time) 1000000)) #+cljs (- (.getTime (js/Date.)) start-time)]
            (>! out {:elapsed-ms elapsed-ms :grid @grid}))))
    {:master-in in :master-out out}))

(defn simulate
"Create a matrix of qXq processor nodes.
Every node is connected to its nearest neighbors (if any)
by four communication channels named north, south, east, and west.
Each processor node is responsible for a subgrid of mXm data cells
within the complete nXn grid, where n = q * m
(m must be even because we did not bother to code for the odd case.)
After initializing its subgrid, each node will update the subgrid
and output its successive values.
The nodes will update their subgrids simultaneously.
The full grid for each step is assembled by the master process
and sent to the out channel.
In numerical analysis, grid iteration is known as relaxation.

The nXn data grid is surrounded by a row of boundary cells on each side.
The application object must specify:
    a fixed value for the elements of each boundary
    and an initial value for the interior elements;
    and a transition function that returns the next value for a cell
    given the subgrid and the cell's position in the subgrid.
"
  [q m application]
  (let [{:keys [initial-values transition]} application
        chan-row #(vec (repeatedly (inc q) chan)) ;; we only use elements 1 through q of chan-row
        chan-matrix #(vec (repeatedly (inc q) chan-row))
        ew-channels (chan-matrix)
        ns-channels (chan-matrix)
        n (* q m)
        init-cell (initializer n initial-values)
        init-subgrid (newgrid m init-cell)
        relax (relaxation q m transition)
        {:keys [master-in master-out]} (master q m n)
        init-node (node init-subgrid relax master-in)]
    
    ;; node coordinates range from 1 to q inclusive
    
    (doseq [i (range 1 (inc q))
            j (range 1 (inc q))]
      (let [neighbors {:north (get-in ns-channels [(dec i) j])
                       :south (get-in ns-channels [i j])
                       :east (get-in ew-channels [i j])
                       :west (get-in ew-channels [i (dec j)])}]
        (init-node i j neighbors)))
    
    master-out))
