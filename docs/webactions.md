# Web Actions

Web actions defers the cost of an action activation from the caller to the owner of the action. These API calls are _not_ authenticated by OpenWhisk and amount to anonymous activations. It is up to the owner to implement their own desired authentication, via an OAuth flow for example, and authorization.

Given some code in a file `hello.js`,
```
$ cat hello.js
function main(args) {
    var msg = "you didn’t tell me who you are."
    if (args.name) {
        msg = `hello ${args.name}!`
    }
    return {html: `<html><body><h3>${msg}</h3></body></html>`}
}
```

the `wsk` command line tool allows you to create and deploy the action to OpenWhisk. For example, to deploy the code as `/guest/demo/hello` (where `guest` is the namespace, `demo` is the package name, and `hello` is the action name):

```
$ wsk action create /guest/demo/hello hello.js
```

which makes the action accessible via a REST interface:

```
$ curl https://APIKEY@APIHOST/api/v1/namespaces/guest/actions/demo/hello?blocking=true \
       -H "Content-Type: application/json" -X POST -d '{"name":”Dave”}'
```

You’ll notice this method requires authentication via an API key. With _web actions_ you can invoke the action without authentication using an experimental API path that will resemble:
```
$ curl https://APIHOST/api/v1/experimental/web/guest/demo/hello.html?name=Dave
```

The action now runs directly in response to an API call. _The owner of the action incurs the cost of activation._

Web actions bring some additional features that include:

1. `Content extensions`: the request must specify its desired content type as one of `.json`, `.html`, `.text` or `.http`. This is done by adding an extension to the action name in the URI, so that an action `/guest/demo/hello` is referenced as `/guest/demo/hello.html` for example to receive an HTML response back.
1. `Projecting fields from the result`: the path that follows the action name is used to project out one or more levels of the response. For example, 
`/guest/demo/hello.html/html`. This allows an action which returns a dictionary `{html: "..." }` to project the `html` property and directly return its string value instead. The project path follows an absolute path model (as in XPath).
1. `Query and body parameters as input:` the action receives query parameters as well as parameters in the request body. The precedence order for merging parameters is: package parameters, action parameters, query parameter, body parameters with each of these overriding any previous values in case of overlap . As an example `/guest/demo/hello.html/html?name=Dave` will pass the argument `{name: "Dave"}` to the action.
1. `Form data`: in addition to the standard `application/json`, web actions may receive URL encoded from data `application/x-www-form-urlencoded data` as input.

## Handling HTTP requests with actions

The `.json` and `.http` extensions do not require a projection path. The `.text` and `.html` extensions do, however for convenience, the default path is assumed to match the extension name. So to invoke a web action and receive an `.html` response, the action must respond with a JSON object that contains a top level property called `html` (or the response must be in the explicitly given path). In other words, `/guest/demo/hello.html` is equivalent to projecting the `html` property explicitly, as in `/guest/demo/hello.html/html`. The fully qualified name of the action must include its package name, which is `default` if the action is not in a named package.

An OpenWhisk action that is not a web action requires both authentication and must respond with a JSON object. In contrast, using web actions it is possible to implement HTTP handlers which respond with _headers_, _status code_, and _body_ content of different types. The web action must still return a JSON object, but the OpenWhisk system (namely the `controller`) will treat an action with the `.http` extension differently if its result includes one or more of the following as top level JSON properties:

1. `headers`: a JSON object where the keys are header-names and the values are string values for those headers (default is no headers).
1. `code`: a valid HTTP status code (default is 200 OK).
1. `body`: a string which is either plain text or a base64 encoded string (for binary data).

The controller will pass along the action-specified headers, if any, to the HTTP client when terminating the request/response. Similarly the controller will respond with the given status code when present. Lastly, the body is passed along as the body of the response. Unless a `content-type header` is declared in the action result’s `headers`, the body is passed along as is if it’s a string (or results in an error otherwise). When the `content-type` is defined, the controller will determine if the response is binary data or plain text and decode the string using a base64 decoder as needed. Should the body fail to decoded correctly, an error is returned to the caller.

Here is an example of a web action that performs an HTTP redirect:
```
function main() {
   return { headers: { location: "http://openwhisk.org" }, 
            code: 302 }
}
```  

Or sets a cookie:
```
function main() {
   return { headers: { "Set-Cookie": "UserID=Jane; Max-Age=3600; Version=" }, 
            code: 200, 
            body: "<html><body><h3>hello</h3></body></html" }
}
```

Or returns an `image/png`:
```
function main() {
    let png = <base 64 encoded string>
    return { headers: { 'Content-Type': 'image/png' },
             code: 200,
             body: png };
}
```

It is important to be aware of the [response size limit](reference.md) for actions when using `.http` response types since a response that exceeds the predefined system limits will fail. Large objects should not be sent inline through OpenWhisk, but instead deferred to an object store, for example.

## HTTP Context

A web action, when invoked, receives all the HTTP request information available as additional parameters to the action input argument. They are:

1. `__ow_meta_verb`: the HTTP method of the request.
2. `__ow_meta_headers`: the request headers.
3. `__ow_meta_path`: the unmatched path of the request (matching stops after consuming the action extension).

The request may not override any of the named `__ow_` parameters above; doing so will result in a failed request with status equal to 400 Bad Request.
Action parameters may also be protected and treated as immutable. To finalize parameters, and to make an action web accessible, two [annotations](annotations.md) must be attached to the action: `final` and `web-export` either of which must be set to `true` to have affect. Revisiting the action deployment earlier, we add the annotations as follows:

```
$ wsk action create /guest/demo/hello hello.js \
      --parameter name Jane \
      --annotation final true \
      --annotation web-export true
```

The result of these changes is that the `name` is bound to `Jane` and may not be overridden by query or body parameters because of the final annotation. This secures the action against query or body parameters that try to change this value whether by accident or intentionally. The `web-export` annotation allows the action to be accessible as web action, namely, from a web browser. To disable a web action, it’s enough to remove the annotation or set it to `false`.

```
$ wsk action update /guest/demo/hello hello.js \
      --annotation web-export false
```      

## Error Handling

When an OpenWhisk action fails, there are two different failure modes. The first is known as an _application error_ and is analogous to a caught exception: the action returns a JSON object containing a top level `error` property. The second is a _developer error_ which occurs when the action fails catastrophically and does not produce a response (this is akin to an uncaught exception). For web actions, the controller handles application errors as follows:

1. Any specified path projection is ignored and the controller projects the `error` property instead.
2. The controller applies the content handling implied by the action extension to the value of the `error` property.

Developers should be aware of how web actions might be used and generate error responses accordingly. For example, a web action that is used with the `.http` extension
should return an HTTP response, for example: `{error: { code: 400 }`. Failing to do so will in a mismatch between the implied content-type from the extension and the action content-type in the error response. Special consideration must be given to web actions that are sequences, so that components that make up a sequence can generate adequate errors when necessary.

## Future Enhancements

It is worthwhile to consider generalizing the `web-export` annotation to provide additional control over which HTTP methods an action is prepared the handle and similarly which content types it supports. One can imagine doing this with a richer `web-export` annotation which may define at least these additional properties:

1. `methods`: array of HTTP methods accepted.
2. `extensions`: array of supported extensions.
3. `authentication`: one of `{"none", "builtin"}` where `none` is for anonymous access and `builtin` for OpenWhisk authentication.

As in `-a web-export '{ "methods": ["get", "post"], "extensions": ["http"], "authentication": "none" }'` for a web action that only accepts `get` and `post` requests, handled `.http` extensions only, and permits anonymous access. A richer set of annotations will allow the controller to reject requests early. The current implementation will accept `get`, `post`, `put` and `delete` HTTP methods without discrimination for any web action.

# Meta Packages

Meta packages, like web actions, defer the cost of an action activation from the caller to the asset owner. Unlike web actions which are not authenticated, actions in a meta package may only be invoked by an authenticated OpenWhisk subject.

We have restricted meta packages to a single designated _system_ namespace, although this may change in the future. The API gateway [integration is implemented entirely as a meta package](../ansible/roles/routemgmt/files/installRouteMgmt.sh). A meta package defines a mapping from HTTP verbs (e.g., POST, GET, DELETE, PUT) to actions in the package that should handle each of these verbs.

An action in a meta package, when invoked, receives all of the information that a web action receives, in addition to the following property that identifies the authenticated subject:
1. `__ow_meta_namespace`: the namespace of the authenticated OpenWhisk user naming the request

A meta action is accessed via the URI `/api/v1/experimental/package-name/action-name[/unmatched/parts/passed/to/action]`. The controller will determine if the named package exists and carries the `meta` annotation and has a proper mapping from the HTTP method to an action. If so, it invokes the action on behalf of the caller but defers the costs of the activation to the meta package owner.
