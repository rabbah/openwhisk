# Annotations on OpenWhisk assets

OpenWhisk actions, triggers, rules and packages (collectively referred to as assets) may be decorated with `annotations`. Annotations are attached to assets just like parameters with a `key` that defines a name and `value` that defines the value. It is convenient to set them from the command line interface (CLI) via `--annotation` or `-a` for short.

Rationale: Annotations were added to OpenWhisk to allow for experimentation without making changes to the underlying asset schema. We had, until the writing of this document, deliberately not defined what `annotations` are permitted. However as we start to use annotations more heavily to impart semantic changes, it's important that we finally start to document them.

The most prevalent use of annotations to date is to document actions and packages. You'll see many of the packages in the OpenWhisk catalog carry annotations such as a description of the functionality offered by their actions, which parameters are required at package binding time, and which are invoke-time parameters, whether a parameter is a "secret" (e.g., password), or not. We have invented these as needed, for example to allow for UI integration.

Here is a sample set of annotations for an `echo` action which returns its input arguments unmodified (e.g., `function main(args) { return args }`). This action may be useful for logging input parameters for example as part of a sequence or rule.

```
wsk action create echo echo.js \
    -a description 'An action which returns its input. Useful for logging input to enable debug/replay.' \
    -a parameters  '[{ "required":false, "description": "Any JSON entity" }]' \
    -a sampleInput  '{ "msg": "Five fuzzy felines"}' \
    -a sampleOutput '{ "msg": "Five fuzzy felines"}'
```

The annotations we have used for describing packages are:

1. `description`: a pithy description of the package
1. `parameters`: an array describing parameters that are scoped to the package (described further below)

Similarly, for actions: 

1. `description`: a pithy description of the action
1. `parameters`: an array describing actions that are required to execute the action
1. `sampleInput`: an example showing the input schema with typical values
1. `sampleOutput`: an example showing the output schema, usually for the `sampleInput`

The annotations we have used for describing parameters include:

1. `name`: the name of the parameter
1. `description`: a pithy description of the parameter
1. `doclink`: a link to further documentation for parameter (useful for OAuth tokens for example) 
1. `required`: true for required parameters and false for optional ones
1. `bindTime`: true if the parameter should be specified when a package is bound
1. `type`: the type of the parameter, one of `password`, `array` (but may be used more broadly)

The annotations are _not_ checked. So while it is conceivable to use the annotations to infer if a composition of two actions into a sequence is legal, for example, the system does not yet do that.

# Annotations for experimental features

We recently extended the core API with some experimental features. To enable packages and actions to participate in these features, we introduced three new annotations that are semantically meaningful. These annotations must be explicitly set to `true` to have affect. Changing the value from `true` to `false` will exclude the attached asset from the experimental API. The annotations have no meaning otherwise in the system. The annotations are:

1. `final`: Applies only to an action. It makes all of the action parameters that are already defined immutable. A parameter of an action carrying the annotation may not be overridden by invoke-time parameters once the parameter has a value defined through its enclosing package or the action definition.
1. `web-export`: Applies only to an action. If present, it makes its corresponding action accessible to REST calls _without_ authentication. We call these [_web actions_](webactions.md) because they allow one to use OpenWhisk actions from a browser for example. It is important to note that the _owner_ of the web action incurs the cost of running them in the system (i.e., the _owner_ of the action also owns the activations record).
1. `meta`: Applies only to a package. A package with this annotation grants any authenticated OpenWhisk users the right to invoke the actions in the package. The cost of the activation is charged to the owner of the package. While any package may carry this annotation, the experimental feature gated by this annotation is currently restricted to a single designated _system_ namespace. 

More details about `web actions` and `meta packages` is available in [webactions.md](webactions.md).
