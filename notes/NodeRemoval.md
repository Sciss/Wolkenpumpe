# Node (Proc) Removal

We need to remove an source outputs connected to any input attributes (params).

The easiest approach would be to require `NuagesAttribute.Input` to provide a new
method `detachSources()`. For any primitive input this is a no-op, except for
`NuagesOutputAttrInput`. For a collection that would have to traverse its children.

## Remove Proc From Folder

### Folder Input

Traverse and remove direct output children

### Grapheme / Timeline Input

Either disallow or remove through deep traversal any direct output children.

## Remove Proc From Timeline

### Folder Input

Traverse and for direct output children, convert them to timeline instances.

### Grapheme / Timeline Input

Traverse intersection and update direct output children.

----------------------------

This doesn't make sense. We don't need to detach sources of the proc to be removed.
Instead we need to find all the sinks of its outputs, and then remove the outputs
from those sinks:

## `!panel.isTimeline`

### Directly in attribute map

    attr.remove(key)
    
### In Folder

    folder.remove(output)
    
### In Grapheme

    unsupported (should not occur). traverse and remove all occurrence
    
### In Timeline

    (unusual). traverse and remove all occurrences

## `panel.isTimeline`

### Directly in attribute map

    convert to Timeline
    
### In Folder

    convert to Timeline
    
### In Grapheme

    unsupported (should not occur). convert to Timeline ?
    
### In Timeline

    update span


