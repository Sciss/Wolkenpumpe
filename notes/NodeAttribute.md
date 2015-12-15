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

