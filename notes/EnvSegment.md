## Challenges

- attr-input should receive aural-stream like its attr
- consumption becomes difficult as obj type changes
- mkConst does not make sense with EnvSegment as return type
- if we want to track fade position in terms of progression color,
  we need info about ceiling element
- we need a mechanism to either block interaction or allow abortion of fade
- in general it would be nice if we need not rely on the aural-stream,
  but use client side interpolation instead?
  