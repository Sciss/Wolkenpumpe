- {OK}  glide ddd
-       fade
-       group filtering
-       fscape procs
-       main recorder
- {low} REPL
-       split screen; split versions; split time
-       creation of attr-link inserts
- {low} auto-mono reduction for patching into params
-       special vis for trigger (0 or 1) signals
- {OK}  keyboard new-object input (typing parts of a name)
- {OK}  keyboard: increase/decrease inter-channel difference
-       undo/redo
-       envelope generator with specific GUI
-       duplicate objects (insert parallel, split tree)
- {OK}  keyboard: x for max: protect (double-press)
- {low} solve param ranges problems
- {OK}  meter for collectors
- {OK}  broken: achil (init cracks) -- to reproduce: tape -> all; insert a-hilb, delete a-hilb, insert achil
- {OK}  broken: frgmnt (silent)
- {OK}  mapping: multi-channel display; clip value
- {OK}  mapping: deleting mapping results in 'mono' param? (sets to DoubleObj?)
-       mono-mix filter, possibly with sample-and-hold option
- {OK?} master meters don't properly show more than eight chans
- {OK}  `Exception in thread "AWT-EventQueue-0" java.lang.ClassCastException: de.sciss.synth.proc.impl.ScanImpl$Impl`
        `cannot be cast to de.sciss.synth.proc.Scan$Link`
        `at	   de.sciss.synth.proc.impl.ScanImpl$Impl$$anonfun$copy$1$$anonfun$apply$mcV$sp$1.apply(ScanImpl.scala:107)`

- {  }  'in' disappears from filter / collector when removing all inputs.
        __workaround:__ alt-click to remove edge. You get an extraneous `in` parameter now,
        but it can be ignored.
- {  }  fine grained param mode (higher keyboard and mouse resolution)
- {  }  tags and tag-based search?
- {  }  copy entire processes (e.g. ctrl-c/v over proc centre?)
- {  }  insert modulators (double click or enter over param?)
- {  }  'dashboard' where you can place params (auto-colour them?)
- {  }  waveform and spectrum / sonogram scope
- {  }  improve default positions of UI nodes (avoid having to wait for the params to "swim away" from the centre)
- {OK}  disconnected nodes (after edge deletion): mouse control is in bad state, for example starting to
        change a parameter or shift-dragging an output additional moves the entire proc around.
        `DragAndMouseDelegateControl` is the only place where `setFixed` is invoked, so we should
        log all things here to see what the wrong boundary condition is.
        The problem is in memorising the last drag source it seems, in `ConnectControl`.
- {  }  resettable timer
- {  }  find a solution for using `setGraph` macro and generate source code

# insert modulators

- modulator proc could indicate which of its own parameters should be initialised
  (e.g. `lo` and `hi` of an oscillator, `value` for `~dc` etc.)
