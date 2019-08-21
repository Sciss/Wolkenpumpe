# Notes 20-Aug-2019

```

val ts    = TimeStamp()
val name  = ts.format("'rec_'yyMMdd'_'HHmmss'.aif'")

```


```

val obj     = invoker.getOrElse(sys.error("ScissProcs.recPrepare - no invoker"))
val name    = recFormatAIFF.format(new java.util.Date)
val nuages  = de.sciss.nuages.Nuages.find[S]()
.getOrElse(sys.error("ScissProcs.recPrepare - Cannot find Nuages instance"))
val loc     = getRecLocation(nuages, findRecDir(obj))
val artM    = Artifact[S](loc, Artifact.Child(name)) // XXX TODO - should check that it is different from previous value
obj.attr.put(attrRecArtifact, artM)

```