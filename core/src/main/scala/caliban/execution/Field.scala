package caliban.execution

import scala.collection.mutable.ArrayBuffer
import caliban.Value.BooleanValue
import caliban.introspection.adt.{ __DeprecatedArgs, __Type }
import caliban.parsing.SourceMapper
import caliban.parsing.adt.Definition.ExecutableDefinition.FragmentDefinition
import caliban.parsing.adt.Selection.{ Field => F, FragmentSpread, InlineFragment }
import caliban.parsing.adt.{ Directive, LocationInfo, Selection, VariableDefinition }
import caliban.schema.{ RootType, Types }
import caliban.{ InputValue, Value }

/**
 * Represents a field used during the exeuction of a query
 *
 * @param name The name
 * @param fieldType The GraphQL type
 * @param parentType The parent type of the field
 * @param alias A potential alias specified in the query, i.e `alias: field`
 * @param fields The selected subfields, if any, i.e `field { a b }`
 * @param targets The type conditions used to select this field, i.e `...on Type { field }`
 * @param arguments The specified arguments for the field's resolver
 * @param directives The directives specified on the field
 * @param _condition Internal, the possible types that contains this field
 * @param _locationInfo Internal, the source location in the query
 */
case class Field(
  name: String,
  fieldType: __Type,
  parentType: Option[__Type],
  alias: Option[String] = None,
  fields: List[Field] = Nil,
  targets: Option[Set[String]] = None,
  arguments: Map[String, InputValue] = Map(),
  directives: List[Directive] = List.empty,
  _condition: Option[Set[String]] = None,
  _locationInfo: () => LocationInfo = () => LocationInfo.origin
) { self =>
  lazy val locationInfo: LocationInfo = _locationInfo()

  def combine(other: Field): Field =
    self.copy(
      fields = self.fields ::: other.fields,
      targets = (self.targets, other.targets) match {
        case (Some(t1), Some(t2)) => if (t1 == t2) self.targets else Some(t1 ++ t2)
        case (Some(_), None)      => self.targets
        case (None, Some(_))      => other.targets
        case (None, None)         => None
      },
      _condition = (self._condition, other._condition) match {
        case (Some(v1), Some(v2)) => if (v1 == v2) self._condition else Some(v1 ++ v2)
        case (Some(_), None)      => self._condition
        case (None, Some(_))      => other._condition
        case (None, None)         => None
      }
    )
}

object Field {
  def apply(
    selectionSet: List[Selection],
    fragments: Map[String, FragmentDefinition],
    variableValues: Map[String, InputValue],
    variableDefinitions: List[VariableDefinition],
    fieldType: __Type,
    sourceMapper: SourceMapper,
    directives: List[Directive],
    rootType: RootType
  ): Field = {
    def loop(selectionSet: List[Selection], fieldType: __Type): Field = {
      val fieldList  = ArrayBuffer.empty[Field]
      val map        = collection.mutable.Map.empty[(String, String), Int]
      var fieldIndex = 0

      def addField(f: Field, condition: Option[String]): Unit = {
        val name = f.alias.getOrElse(f.name)
        val key  = (name, condition.getOrElse(""))
        map.get(key) match {
          case None        =>
            // first time we see this field, add it to the array
            fieldList += f
            map.update(key, fieldIndex)
            fieldIndex = fieldIndex + 1
          case Some(index) =>
            // field already existed, merge it
            val existing = fieldList(index)
            fieldList(index) = existing.combine(f)
        }
      }

      val innerType = Types.innerType(fieldType)
      selectionSet.foreach {
        case F(alias, name, arguments, directives, selectionSet, index) =>
          val selected = innerType
            .fields(__DeprecatedArgs(Some(true)))
            .flatMap(_.find(_.name == name))

          val schemaDirectives   = selected.flatMap(_.directives).getOrElse(Nil)
          val resolvedDirectives = (directives ++ schemaDirectives).map(directive =>
            directive.copy(arguments = resolveVariables(directive.arguments, variableDefinitions, variableValues))
          )

          if (checkDirectives(resolvedDirectives)) {
            val t = selected.fold(Types.string)(_.`type`()) // default only case where it's not found is __typename

            val field = loop(selectionSet, t)

            addField(
              Field(
                name,
                t,
                Some(innerType),
                alias,
                field.fields,
                None,
                resolveVariables(arguments, variableDefinitions, variableValues),
                resolvedDirectives,
                None,
                () => sourceMapper.getLocation(index)
              ),
              None
            )
          }
        case FragmentSpread(name, directives)                           =>
          val resolvedDirectives = directives.map(directive =>
            directive.copy(arguments = resolveVariables(directive.arguments, variableDefinitions, variableValues))
          )

          if (checkDirectives(resolvedDirectives)) {
            fragments
              .get(name)
              .foreach { f =>
                val t =
                  innerType.possibleTypes.flatMap(_.find(_.name.contains(f.typeCondition.name))).getOrElse(fieldType)
                loop(f.selectionSet, t).fields
                  .map(field =>
                    if (field._condition.isDefined) field
                    else
                      field.copy(
                        targets = Some(Set(f.typeCondition.name)),
                        _condition = subtypeNames(f.typeCondition.name, rootType)
                      )
                  )
                  .foreach(addField(_, Some(f.typeCondition.name)))
              }
          }
        case InlineFragment(typeCondition, directives, selectionSet)    =>
          val resolvedDirectives = directives.map(directive =>
            directive.copy(arguments = resolveVariables(directive.arguments, variableDefinitions, variableValues))
          )

          if (checkDirectives(resolvedDirectives)) {
            val t     = innerType.possibleTypes
              .flatMap(_.find(_.name.exists(typeCondition.map(_.name).contains)))
              .getOrElse(fieldType)
            val field = loop(selectionSet, t)
            typeCondition match {
              case None           => if (field.fields.nonEmpty) fieldList ++= field.fields
              case Some(typeName) =>
                field.fields
                  .map(field =>
                    if (field._condition.isDefined) field
                    else
                      field
                        .copy(
                          targets = typeCondition.map(t => Some(Set(t.name))).getOrElse(None),
                          _condition = subtypeNames(typeName.name, rootType)
                        )
                  )
                  .foreach(addField(_, Some(typeName.name)))
            }
          }
      }
      Field("", fieldType, None, fields = fieldList.toList)
    }

    loop(selectionSet, fieldType).copy(directives = directives)
  }

  private def resolveVariables(
    arguments: Map[String, InputValue],
    variableDefinitions: List[VariableDefinition],
    variableValues: Map[String, InputValue]
  ): Map[String, InputValue] = {
    def resolveVariable(value: InputValue): Option[InputValue] =
      value match {
        case InputValue.ListValue(values)   =>
          Some(InputValue.ListValue(values.flatMap(resolveVariable)))
        case InputValue.ObjectValue(fields) =>
          Some(InputValue.ObjectValue(fields.flatMap { case (k, v) => resolveVariable(v).map(k -> _) }))
        case InputValue.VariableValue(name) =>
          for {
            definition <- variableDefinitions.find(_.name == name)
            value      <- variableValues.get(name).orElse(definition.defaultValue)
          } yield value
        case value: Value                   =>
          Some(value)
      }
    arguments.flatMap { case (k, v) => resolveVariable(v).map(k -> _) }
  }

  private def subtypeNames(typeName: String, rootType: RootType): Option[Set[String]] =
    rootType.types
      .get(typeName)
      .map(t =>
        t.possibleTypes
          .fold(Set.empty[String])(
            _.map(_.name.map(subtypeNames(_, rootType).getOrElse(Set.empty))).toSet.flatten.flatten
          ) + typeName
      )

  private def checkDirectives(directives: List[Directive]): Boolean =
    !checkDirective("skip", default = false, directives) &&
      checkDirective("include", default = true, directives)

  private def checkDirective(name: String, default: Boolean, directives: List[Directive]): Boolean =
    directives
      .find(_.name == name)
      .flatMap(_.arguments.get("if")) match {
      case Some(BooleanValue(value)) => value
      case _                         => default
    }
}
