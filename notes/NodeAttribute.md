# Scenarios

- single scalar
- single scalar inside folder / timeline
- single output
- single output inside folder / timeline
- multiple

## single scalar, single scalar inside folder / timeline

The single scalar becomes the only visible and editable node inside the aggregate

## single single output, single output inside folder / timeline

The output is monitored through the only visible end non-editable node inside the aggregate

## multiple

The combined output is monitored through the only visible end non-editable node inside the aggregate.
For each scalar, an external editable node is added as well.

# Node Creations and Transitions

- Assume a difference between `nuages-attribute` and `nuages-attribute-input`
- For each attribute, there is a single `nuages-attribute` (e.g. it carries the `spec`)
- Nested folders should 'flatten'

This makes me think, we should treat Folder/Timeline directly and not through some general factory.
It also makes transitions easier, because if we obey that introducing a folder is done by first adding
the previous element to it before overwriting the attribute entry, we can easily figure out that the
attribute hasn't changed, preventing a nasty `PNode` removal and re-creation.

We generally don't want a mixed folder of `Output` and `Int` and such, so while that must be
supported we don't need to support that as a default view option. For example if an output is mapped
to a attribute that previously held a scalar, we simply overwrite that entry.

We currently have a `NuagesShapeRenderer` assuming an ellipse raw shape. This could perhaps entirely
move to the nuages-attribute. Internal and external nodes would still hook each up with an `NuagesData` object.
There is also no reason we couldn't give up `AbstractShapeRenderer` altogether and use the plain `Renderer`.

Let's start by looking at the nuages-obj perspective, i.e. its single `nuages-attribute` thing.

    trait NuagesAttribute {
      def addNode   (n: PNode): Unit
      def removeNode(n: PNode): Unit
    }
    
This would allow an output to add the existing source object's existing node, a scalar to create and add its
new node, a folder to iterate over its children etc.

How do we distinguish between an existing or external node and one that should go inside the target aggregate?
Perhaps something simple like adding a switch:

    trait NuagesAttribute {
      def addNode   (n: PNode, isFree: Boolean): Unit
      def removeNode(n: PNode): Unit
    }

We must be careful to think big&mdash;for example, let's assume an attribute could be an audio file for which
we'd have an external floating view. Thus, there is types which are never internalised.

-----------------

Consider the nested case: Folder(Folder(Int)) - flattened this is one scalar which should be internally displayed.
What happens if another element is added to the inner folder?

- we need an observable trigger for announcing the growth / the movement of the internalised node to the outside
- the trigger must be generated in the internal folder, thus it propagates both down (moving the int's node)
  and up (notifying the parent that it must start rendering a 'summary' node)
- this second step could be indirect; for instance, if the inner folder issues the creation of a new node for
  the scalar, the adding of that node to the managing attribute may cause that attribute to start accumulation?
- remember that a (global) renderer goes from PNode to nuages-data; this makes it easy to 'externalise' an
  attribute-input

Still, the above interface (add with flag, remove) should be sufficient. The managing instance needs to count
the free and non-free nodes:

- `case (0, 0) =>` nada
- `case (1, 0) =>` internalise
- `case _ =>` summary (aux) node

-----------------

# Updating controls

Say the implementation type is `A` (e.g. `IntObj`, `DoubleObj`, `DoubleVector`).
For `!isTimeline`:

- if we have `A.Var`, we simply update the var. So it doesn't
  matter whether the parent is `.attr` or a `Folder` or a `Timeline`.
- if we do not have `A.Var` -- we currently ignore that case and `editable == false`
   
For `isTimeline`:

- we should never ask for `A.Var`, but we do create `A.Var` instances, so the stuff
  becomes editable for example in an offline timeline view or if we play a "slice"
  in Nuages without transport (could we have a `timeline.viewAsFolderAt(time)` view?)
- (well, not necessarily)
- the parent could be `.attr` (was scalar until now), `Folder` (was scalar until now),
  or `Timeline`.
- the result must be a value on a `Timeline`.
- clearly we need a parent object for the `Input` that is not necessarily the 
  `NuagesAttribute` itself
  
Let's say we always have this new type of parent object, no matter `isTimeline`.
Then we can simplify by assuming we have a `Type.Expr` so we know there are constants
and variables. The `Input` then merely needs to provide a new constant, and the parent
can handle all editing (as outlined in the list above).

Time in the timeline should always be relative to the creation time of the parent object.
If makes sense to first implement the removal of objects from the peer, because at that
point we need to figure out how to preserve the `SpanLike` association.

## `NuagesTimelineAttrInput`

We need to relative offset of the parent obj to the nuages transport. Perhaps `NuagesObj`
could carry `Proc` or `Timeline.Entry` (which is an `Obj` as well)?