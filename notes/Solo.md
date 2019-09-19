# Solo - 190919

    NuagesPanel > PanelImplMixer : mkPeakMeter (transactional)

- is called from `NuagesOutputImpl`


    NuagesPanel > PanelImplMixer : setSolo (non-transactional)

- is called from `NuagesObjImpl`

It would be better if `setSolo` was transactional instead; the implementation 
anyway goes straight to `cursor.step`. Then `setSolo` should have the function
of registering which object is in solo mode, not actually creating the solo
audio structure. This should be done in `NuagesOutputImpl` because there is
where we trace the aural object and have access to the bus. And there is where
we can have the disposal code so we don't leave things unclean by mistake.
Then we should add `mkSoloSynth` to `NuagesPanel`.

The `setSolo` is kind of ugly, because the panel will call back into the views
setting the `soloed` flag. So it becomes messy. Do we need to expose `soled`?
The answer is _no_. But we need to have a `clearSolo` method on `NuagesObj`.

So we have

- mouse pressed (UI)
  - run transaction:
  - is solo'ed?
    - yes: call `setSolo(None)` on `NuagesPanel`. That will remove its
      reference and call back into `NuagesObj : soloed = false`. Not particularly
      nice, but can't seem a better alternative
    - no: call `setSolo(Some)` on `NuagesPanel`. That will call `clearSolo` on
      the previous object, if it exists, then call `NuagesObj : soloed = true`.
      
 The implementation for `def soloed_=(value: Boolean)(implicit tx: S#Tx)` then
 basically forwards the call to `NuagesOutputImpl`, and sets its own UI drawing
 flag.
 
 BTW, `var meterSynth` is not used in `NuagesObjImpl`.
 