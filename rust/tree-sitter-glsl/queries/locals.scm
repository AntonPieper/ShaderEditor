;; Functions definitions
(function_declarator
  declarator: (identifier) @local.definition)
(preproc_function_def
  name: (identifier)) @local.scope

(preproc_def
  name: (identifier) @local.definition)
(pointer_declarator
  declarator: (identifier) @local.definition)
(parameter_declaration
  declarator: (identifier) @local.definition)
(init_declarator
  declarator: (identifier) @local.definition)
(array_declarator
  declarator: (identifier) @local.definition)
(declaration
  declarator: (identifier) @local.definition)
(enum_specifier
  name: (_) @local.definition
  (enumerator_list
    (enumerator name: (identifier) @local.definition)))

;; Type / Struct / Enum
(field_declaration
  declarator: (field_identifier) @local.definition)
(type_definition
  declarator: (type_identifier) @local.definition)
(struct_specifier
  name: (type_identifier) @local.definition)

;; goto
(labeled_statement (statement_identifier) @local.definition)

;; References
(identifier) @local.reference
(field_identifier) @local.reference
(type_identifier) @local.reference

(goto_statement (statement_identifier) @local.reference)

;; Scope
[
  (for_statement)
  (if_statement)
  (while_statement)
  (translation_unit)
  (function_definition)
  (compound_statement) ; a block in curly braces
  (struct_specifier)
  ] @local.scope