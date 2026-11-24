# Request rules

A rule set says what a request must contain. It is a JSON text, and Mockey uses it in two places:

- **The request rules of a service** inspect every request that the service gets, while they are enabled.
  Each rule that a request fails gives a message in the history entry of that request. The answer to the
  request stays the same.
- **The rule-set criterion of a scenario** decides whether the scenario can answer a request in dynamic mode.
  The scenario matches when the request satisfies the rule set, and its score is the number of rules that the
  request satisfies.

Both places use the same format and the same evaluation. The format is that of Mockey 2.x, so the rule sets of
existing definitions work as they are. "Differences from Mockey 2.x" at the end lists what Mockey evaluates in
another way than Mockey 2.x did.

## Format

A rule set is a JSON object. Each of its members is a group, and each group holds an array of rules:

```json
{
  "parameters": [
    {
      "key": "ticker",
      "desc": "The ticker must contain the letter g: GOOG passes, FB does not.",
      "value_rule_arg": "g",
      "value_rule_type": "string_required"
    },
    {
      "key": "date",
      "desc": "A date is optional. When there is one, it must look like 11/23/2026.",
      "value_rule_arg": "^\\d{2}/\\d{2}/\\d{4}$",
      "value_rule_type": "regex_optional"
    }
  ],
  "headers": [
    {
      "key": "page_id",
      "desc": "page_id is mandatory",
      "value_rule_arg": "",
      "value_rule_type": "string_required"
    }
  ],
  "body": [
    {
      "desc": "The body must have an amount.",
      "value_rule_arg": "\"amount\":\\s*\\d+",
      "value_rule_type": "regex_required"
    }
  ],
  "url": [
    {
      "desc": "The URL must name the customer 123.",
      "value_rule_arg": "\\b123\\b",
      "value_rule_type": "regex_required"
    }
  ]
}
```

### Groups

A group says what of the request its rules evaluate. A rule set can have any of the four groups, and no other
member.

| Group | What its rules evaluate |
|---|---|
| `parameters` | The parameters of the query string and the fields of a URL-encoded form. |
| `headers` | The headers of the request. |
| `body` | The body of the request, as text. |
| `url` | The URL that the client addressed, without its query string. |

- **Parameters.** The body of a request is a URL-encoded form when its `Content-Type` is
  `application/x-www-form-urlencoded`. Names and values are decoded before a rule sees them: a `+` is a space,
  and `%2F` is a `/`.
- **Headers.** A rule sees every header that the client sent, also `Host`, `Cookie`, `Content-Length`, and
  `Accept-Encoding`.
- **Body.** The body is read in the charset that the `Content-Type` of the request declares, and as UTF-8
  when it declares none. A body that holds nothing but whitespace counts as no body.
- **URL.** The URL is the one of the mock endpoint, as the client wrote it, such as
  `http://mockey.test:8080/service/customer/123`. Percent escapes in it are not decoded. The query string is
  no part of it: a `parameters` rule looks at that.

### Rules

A rule is a JSON object with these members:

| Member | Required | Meaning |
|---|---|---|
| `value_rule_type` | Yes | How the rule evaluates: `string_required`, `regex_required`, or `regex_optional`, in any letter case. |
| `value_rule_arg` | Yes | What the type looks for: a text or a regular expression. It can be empty. |
| `key` | In `parameters` and `headers` | The name of the parameter or the header that the rule evaluates. The letter case of the name does not count. The key `*` stands for all parameters or all headers. In `body` and `url`, a key means nothing. |
| `desc` | No | A description of the rule. It is part of the message that a failed rule gives. |

The values of `key`, `value_rule_arg`, and `desc` are texts. A number or a boolean stands for the text that it
is written as, so `44` is the text `44`. A member that is `null` counts as absent. Any other member of a rule
means nothing.

### Rule types

A rule evaluates values: the values of its parameter or header, the body, or the URL. A parameter or a header
can occur several times in a request, and the key `*` takes the values of all of them. One value that fits is
enough.

| Type | Satisfied when | Without a value |
|---|---|---|
| `string_required` | A value contains the argument. Letter case does not count, and neither does whitespace around the argument. An empty argument asks for no more than a value. | Fails |
| `regex_required` | The regular expression is found in a value. | Fails |
| `regex_optional` | The regular expression is found in a value. | Satisfied |

A rule has no value when the request does not have its parameter or header, when the key is `*` and the
request has no parameter at all, or when the group is `body` and the request has no body.

The regular expressions are those of Java (`java.util.regex`).

- An expression is searched for in a value, so `\d+` is found in `order 66`. Write `^\d+$` when the whole
  value must fit.
- Letter case counts in an expression. Start the expression with `(?i)` when it must not.
- JSON needs a backslash twice: the expression `\d+` is written as `"\\d+"`.

## When a rule set is satisfied

- A group is satisfied when the request satisfies every rule of it.
- A rule set is satisfied when at least one of its groups is satisfied. Only a group with a rule counts.
- A rule set without any rule is never satisfied.

The rules of one group are therefore joined by "and", and the groups by "or". The following rule set is
satisfied by a request with the header `X-HTTP-Method-Override: PUT`, and by a request for a URL that
contains `44`, whatever its headers are:

```json
{
  "headers": [
    {"key": "x-http-method-override", "value_rule_arg": "PUT", "value_rule_type": "string_required"}
  ],
  "url": [
    {"value_rule_arg": "44", "value_rule_type": "string_required"}
  ]
}
```

The score of a rule-set criterion is the number of rules that the request satisfies, in all groups. A rule of
a group that is not satisfied as a whole counts too, and so does an optional rule that has no value.

## Rule sets that are not well formed

Mockey checks a rule set when a service or a scenario is saved. It does not save a service whose request rules
are not well formed, whether or not they are enabled, or a scenario whose rule-set criterion is not, and it
says what is wrong. A rule set is not well formed when one of these is true:

- The text is not JSON, or it is JSON but no object. Single quotes, comments, and a comma after the last
  entry are not JSON.
- A member is none of the four groups. The names of the groups are in lower case.
- A group is not an array, or an entry of a group is not an object.
- A rule has no `value_rule_type`, or a type that is none of the three.
- A rule has no `value_rule_arg`.
- A rule of `parameters` or `headers` has no `key`.
- The `value_rule_arg` of a `regex_required` or `regex_optional` rule is not a regular expression.
- A member of a rule that must be a text is an object or an array.

Every fault is named, with the group and the number of the rule:

```text
Rule 1 of the group 'headers' has no 'key'.
Rule 2 of the group 'parameters' has the 'value_rule_type' 'number_required'. The types are 'string_required', 'regex_required', and 'regex_optional'.
Rule 1 of the group 'body' has the 'value_rule_arg' '([a-z', which is not a regular expression: Unclosed character class near index 4.
```

Definitions that come from elsewhere can hold a rule set that is not well formed, such as definitions that
were imported from Mockey 2.x. Mockey keeps the text of such a rule set and treats it like this:

- The rule set is never satisfied. As the criterion of a scenario, it lets the scenario answer no request in
  dynamic mode.
- As the enabled request rules of a service, it gives every request of the service one message, which says
  that the request rules of the service could not be read.
- The definition diagnostics report the rule set and say what is wrong with it.
- The service or the scenario cannot be saved again until the rule set is repaired or removed.

## Inspection messages

The request rules of a service inspect only while they are enabled. Every rule that a request fails gives one
message, also when the rule set as a whole is satisfied through another group. A message names the group of
the rule, its key where it has one, what the rule requires, and the description of the rule.

A request for `http://mockey.test:8080/service/customer/123?ticker=msft` without a `page_id` header and
without a body fails three rules of the rule set under "Format":

```text
The request fails a rule of the group 'parameters' for the key 'ticker', which requires a value that contains 'g'. Description: The ticker must contain the letter g: GOOG passes, FB does not.
The request fails a rule of the group 'headers' for the key 'page_id', which requires a value. Description: page_id is mandatory
The request fails a rule of the group 'body', which requires a value in which the regular expression '"amount":\s*\d+' is found. Description: The body must have an amount.
```

The optional rule for `date` is satisfied, because the request has no `date`. The rule of `url` is satisfied
too, and with it the rule set.

## Differences from Mockey 2.x

Mockey reads the rule sets of Mockey 2.x, and it evaluates them in the same way except for these points.
`docs/deviations.md` describes each of them.

| ID | Mockey 2.x | Mockey |
|---|---|---|
| DEV-15 | A rule whose parameter or header is not in the request is skipped. | A required rule fails. An optional rule is satisfied and counts. |
| DEV-16 | Header rules do not see `Host`, `Cookie`, `Content-Length`, `Accept-Encoding`, and `If-None-Match`. | Header rules see every header. |
| DEV-17 | Parameter rules do not see the fields of a form. | Parameter rules see the query string and the form. |
| DEV-44 | Every key that contains a `*` stands for all parameters or headers. | Only the key `*` does. |
| DEV-45, DEV-54 | A rule set with a fault is evaluated as far as it can be read. | A rule set with a fault is not well formed. It is never satisfied, and it cannot be saved. |
| DEV-55 | An optional rule with the key `*` fails when the request has no parameter. | The rule is satisfied. |
| DEV-56 | A rule is evaluated once for each spelling of its key in the request, such as `Ticker` and `ticker`. | The rule is evaluated once, with the values of all spellings. |
| DEV-57 | The body is read in the default charset of the Java runtime. | The body is read in the charset that the request declares, or as UTF-8. |
