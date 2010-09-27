/* NSC -- new Scala compiler
 * Copyright 2005-2010 LAMP/EPFL
 * @author  Martin Odersky
 */

package scala.tools.nsc
package backend
package icode

/* A type case

    case UNIT            =>
    case BOOL            =>
    case BYTE            =>
    case SHORT           =>
    case CHAR            =>
    case INT             =>
    case LONG            =>
    case FLOAT           =>
    case DOUBLE          =>
    case REFERENCE(cls)  =>
    case ARRAY(elem)     =>

*/

trait TypeKinds { self: ICodes =>
  import global._
  import definitions.{ ArrayClass, AnyRefClass, ObjectClass, NullClass, NothingClass }
  import icodes.checkerDebug

  /** A map from scala primitive Types to ICode TypeKinds */
  lazy val primitiveTypeMap: Map[Symbol, TypeKind] = {
    import definitions._
    Map(
      UnitClass     -> UNIT,
      BooleanClass  -> BOOL,
      CharClass     -> CHAR,
      ByteClass     -> BYTE,
      ShortClass    -> SHORT,
      IntClass      -> INT,
      LongClass     -> LONG,
      FloatClass    -> FLOAT,
      DoubleClass   -> DOUBLE
    )
  }
  /** Reverse map for toType */
  private lazy val reversePrimitiveMap: Map[TypeKind, Symbol] =
    primitiveTypeMap map (_.swap) toMap

  /** This class represents a type kind. Type kinds
   * represent the types that the VM know (or the ICode
   * view of what VMs know).
   */
  sealed abstract class TypeKind {
    def maxType(other: TypeKind): TypeKind

    def toType: Type = (reversePrimitiveMap get this) map (_.tpe) getOrElse {
      this match {
        case REFERENCE(cls)  => cls.tpe
        case ARRAY(elem)     => typeRef(ArrayClass.typeConstructor.prefix, ArrayClass, List(elem.toType))
        case _ => abort("Unknown type kind.")
      }
    }
    def toTypeAt(ph: Phase): Type = atPhase(ph)(toType)

    def isReferenceType        = false
    def isArrayType            = false
    def isValueType            = false
    final def isRefOrArrayType = isReferenceType || isArrayType
    final def isNothingType    = this == REFERENCE(NothingClass)

    def isIntegralType: Boolean = this match {
      case BYTE | SHORT | INT | LONG | CHAR => true
      case _                                => false
    }
    def isRealType: Boolean = this match {
      case FLOAT | DOUBLE => true
      case _              => false
    }
    def isNumericType: Boolean = isIntegralType | isRealType

    /** Simple subtyping check */
    def <:<(other: TypeKind): Boolean = (this eq other) || (this match {
      case BOOL | BYTE | SHORT | CHAR => other == INT || other == LONG
      case _                          => this eq other
    })

    /** Is this type a category 2 type in JVM terms? */
    def isWideType: Boolean = this match {
      case DOUBLE | LONG  => true
      case _              => false
    }

    /** The number of dimensions for array types. */
    def dimensions: Int = 0

    protected def uncomparable(thisKind: String, other: TypeKind): Nothing =
      abort("Uncomparable type kinds: " + thisKind + " with " + other)

    protected def uncomparable(other: TypeKind): Nothing =
      uncomparable(this.toString, other)
  }

  sealed abstract class ValueTypeKind extends TypeKind {
    override def isValueType = true
    override def toString = {
      this.getClass.getName stripSuffix "$" dropWhile (_ != '$') drop 1
    }
  }

  var lubs0 = 0

  /**
   * The least upper bound of two typekinds. They have to be either
   * REFERENCE or ARRAY kinds.
   *
   * The lub is based on the lub of scala types.
   */
  def lub(a: TypeKind, b: TypeKind): TypeKind = {
    def lub0(tk1: TypeKind, tk2: TypeKind): Type = {
      /** Returning existing implementation unless flag given.  See #3872. */
      if (!isCheckerDebug)
        return global.lub(List(tk1.toType, tk2.toType))

      /** PP: Obviously looking for " with " is a bit short of the ideal robustness,
       *  but that's why it's only used under -Ycheck-debug.  Correct fix is likely
       *  to change compound types to lead with the class type.
       */
      atPhase(currentRun.typerPhase) {
        val t1 = tk1.toType
        val t2 = tk2.toType
        val calculated = global.lub(List(t1, t2))
        checkerDebug("at Phase %s, lub0(%s, %s) == %s".format(global.globalPhase, t1, t2, calculated))
        calculated match {
          case x: CompoundType =>
            val tps = x.baseTypeSeq.toList filterNot (_.toString contains " with ")
            val id  = global.erasure.erasure.intersectionDominator(tps)
            checkerDebug("intersectionDominator(%s) == %s".format(tps.mkString(", "), id))
            id
          case x => x
        }
      }
    }

    if (a == b) a
    else if (a.isNothingType) b
    else if (b.isNothingType) a
    else (a, b) match {
      case (BOXED(a1), BOXED(b1)) => if (a1 == b1) a else REFERENCE(AnyRefClass)
      case (BOXED(_), REFERENCE(_)) | (REFERENCE(_), BOXED(_)) => REFERENCE(AnyRefClass)
      case (BOXED(_), ARRAY(_)) | (ARRAY(_), BOXED(_)) => REFERENCE(AnyRefClass)
      case (BYTE, INT) | (INT, BYTE) => INT
      case (SHORT, INT) | (INT, SHORT) => INT
      case (CHAR, INT) | (INT, CHAR) => INT
      case (BOOL, INT) | (INT, BOOL) => INT
      case _ =>
        if (a.isRefOrArrayType && b.isRefOrArrayType)
          toTypeKind(lub0(a, b))
        else
          throw new CheckerException("Incompatible types: " + a + " with " + b)
    }
  }

  /** The unit value */
  case object UNIT extends ValueTypeKind {
    def maxType(other: TypeKind) = other match {
      case UNIT | REFERENCE(NothingClass)   => UNIT
      case _                                => uncomparable(other)
    }
  }

  /** A boolean value */
  case object BOOL extends ValueTypeKind {
    def maxType(other: TypeKind) = other match {
      case BOOL | REFERENCE(NothingClass)   => BOOL
      case _                                => uncomparable(other)
    }
  }

  /** Note that the max of Char/Byte and Char/Short is Int, because
   *  neither strictly encloses the other due to unsignedness.
   *  See ticket #2087 for a consequence.
   */

  /** A 1-byte signed integer */
  case object BYTE extends ValueTypeKind {
    def maxType(other: TypeKind) = {
      if (other == BYTE || other.isNothingType) BYTE
      else if (other == CHAR) INT
      else if (other.isNumericType) other
      else uncomparable(other)
    }
  }

  /** A 2-byte signed integer */
  case object SHORT extends ValueTypeKind {
    override def maxType(other: TypeKind) = other match {
      case BYTE | SHORT | REFERENCE(NothingClass) => SHORT
      case CHAR                                   => INT
      case INT | LONG | FLOAT | DOUBLE            => other
      case _                                      => uncomparable(other)
    }
  }

  /** A 2-byte UNSIGNED integer */
  case object CHAR extends ValueTypeKind {
    override def maxType(other: TypeKind) = other match {
      case CHAR | REFERENCE(NothingClass) => CHAR
      case BYTE | SHORT                   => INT
      case INT | LONG | FLOAT | DOUBLE    => other
      case _                              => uncomparable(other)
    }
  }

  /** A 4-byte signed integer */
  case object INT extends ValueTypeKind {
    override def maxType(other: TypeKind) = other match {
      case BYTE | SHORT | CHAR | INT | REFERENCE(NothingClass)  => INT
      case LONG | FLOAT | DOUBLE                                => other
      case _                                                    => uncomparable(other)
    }
  }

  /** An 8-byte signed integer */
  case object LONG extends ValueTypeKind {
    override def maxType(other: TypeKind): TypeKind =
      if (other.isIntegralType || other.isNothingType) LONG
      else if (other.isRealType) DOUBLE
      else uncomparable(other)
  }

  /** A 4-byte floating point number */
  case object FLOAT extends ValueTypeKind {
    override def maxType(other: TypeKind): TypeKind =
      if (other == DOUBLE) DOUBLE
      else if (other.isNumericType || other.isNothingType) FLOAT
      else uncomparable(other)
  }

  /** An 8-byte floating point number */
  case object DOUBLE extends ValueTypeKind {
    override def maxType(other: TypeKind): TypeKind =
      if (other.isNumericType || other.isNothingType) DOUBLE
      else uncomparable(other)
  }

  /** A class type. */
  final case class REFERENCE(cls: Symbol) extends TypeKind {
    assert(cls ne null,
           "REFERENCE to null class symbol.")
    assert(cls != ArrayClass,
           "REFERENCE to Array is not allowed, should be ARRAY[..] instead")
    assert(cls != NoSymbol,
           "REFERENCE to NoSymbol not allowed!")

    /**
     * Approximate `lub'. The common type of two references is
     * always AnyRef. For 'real' least upper bound wrt to subclassing
     * use method 'lub'.
     */
    override def maxType(other: TypeKind) = other match {
      case REFERENCE(_) | ARRAY(_)  => REFERENCE(AnyRefClass)
      case _                        => uncomparable("REFERENCE", other)
    }

    /** Checks subtyping relationship. */
    override def <:<(other: TypeKind) = isNothingType || (other match {
      case REFERENCE(cls2)  => cls.tpe <:< cls2.tpe
      case ARRAY(_)         => cls == NullClass
      case _                => false
    })
    override def isReferenceType = true
  }

  def ArrayN(elem: TypeKind, dims: Int): ARRAY = {
    assert(dims > 0)
    if (dims == 1) ARRAY(elem)
    else ARRAY(ArrayN(elem, dims - 1))
  }

  final case class ARRAY(val elem: TypeKind) extends TypeKind {
    override def toString    = "ARRAY[" + elem + "]"
    override def isArrayType = true
    override def dimensions  = 1 + elem.dimensions

    /** The ultimate element type of this array. */
    def elementKind: TypeKind = elem match {
      case a @ ARRAY(_) => a.elementKind
      case k            => k
    }

    /**
     * Approximate `lub'. The common type of two references is
     * always AnyRef. For 'real' least upper bound wrt to subclassing
     * use method 'lub'.
     */
    override def maxType(other: TypeKind) = other match {
      case ARRAY(elem2) if elem == elem2  => ARRAY(elem)
      case ARRAY(_) | REFERENCE(_)        => REFERENCE(AnyRefClass)
      case _                              => uncomparable("ARRAY", other)
    }

    /** Array subtyping is covariant, as in Java. Necessary for checking
     *  code that interacts with Java. */
    override def <:<(other: TypeKind) = other match {
      case ARRAY(elem2)                         => elem <:< elem2
      case REFERENCE(AnyRefClass | ObjectClass) => true // TODO: platform dependent!
      case _                                    => false
    }
  }

  /** A boxed value. */
  case class BOXED(kind: TypeKind) extends TypeKind {
    /**
     * Approximate `lub'. The common type of two references is
     * always AnyRef. For 'real' least upper bound wrt to subclassing
     * use method 'lub'.
     */
    override def maxType(other: TypeKind) = other match {
      case REFERENCE(_) | ARRAY(_) | BOXED(_) => REFERENCE(AnyRefClass)
      case _                                  => uncomparable("BOXED", other)
    }

    /** Checks subtyping relationship. */
    override def <:<(other: TypeKind) = other match {
      case BOXED(other)                         => kind == other
      case REFERENCE(AnyRefClass | ObjectClass) => true // TODO: platform dependent!
      case _                                    => false
    }
  }

 /**
  * Dummy TypeKind to represent the ConcatClass in a platform-independent
  * way. For JVM it would have been a REFERENCE to 'StringBuffer'.
  */
  case object ConcatClass extends TypeKind {
    override def toString = "ConcatClass"

    /**
     * Approximate `lub'. The common type of two references is
     * always AnyRef. For 'real' least upper bound wrt to subclassing
     * use method 'lub'.
     */
    override def maxType(other: TypeKind) = other match {
      case REFERENCE(_) => REFERENCE(AnyRefClass)
      case _            => uncomparable(other)
    }

    /** Checks subtyping relationship. */
    override def <:<(other: TypeKind) = this eq other
  }

  ////////////////// Conversions //////////////////////////////

  /** Return the TypeKind of the given type
   *
   *  Call to .normalize fixes #3003 (follow type aliases). Otherwise,
   *  arrayOrClassType below would return ObjectReference.
   */
  def toTypeKind(t: Type): TypeKind = t.normalize match {
    case ThisType(ArrayClass)            => ObjectReference
    case ThisType(sym)                   => REFERENCE(sym)
    case SingleType(_, sym)              => primitiveOrRefType(sym)
    case ConstantType(_)                 => toTypeKind(t.underlying)
    case TypeRef(_, sym, args)           => primitiveOrClassType(sym, args)
    case ClassInfoType(_, _, ArrayClass) => abort("ClassInfoType to ArrayClass!")
    case ClassInfoType(_, _, sym)        => primitiveOrRefType(sym)
    case ExistentialType(_, t)           => toTypeKind(t)
    case AnnotatedType(_, t, _)          => toTypeKind(t)
    // bq: useful hack when wildcard types come here
    // case WildcardType                    => REFERENCE(ObjectClass)
    case norm => abort(
      "Unknown type: %s, %s [%s, %s] TypeRef? %s".format(
        t, norm, t.getClass, norm.getClass, t.isInstanceOf[TypeRef]
      )
    )
  }

  /** Return the type kind of a class, possibly an array type.
   */
  private def arrayOrClassType(sym: Symbol, targs: List[Type]) = sym match {
    case ArrayClass       => ARRAY(toTypeKind(targs.head))
    case _ if sym.isClass => REFERENCE(sym)
    case _                =>
      assert(sym.isType, sym) // it must be compiling Array[a]
      ObjectReference
  }
  private def primitiveOrRefType(sym: Symbol) =
    primitiveTypeMap.getOrElse(sym, REFERENCE(sym))
  private def primitiveOrClassType(sym: Symbol, targs: List[Type]) =
    primitiveTypeMap.getOrElse(sym, arrayOrClassType(sym, targs))

  def msil_mgdptr(tk: TypeKind): TypeKind = (tk: @unchecked) match {
    case REFERENCE(cls)  => REFERENCE(loaders.clrTypes.mdgptrcls4clssym(cls))
    // TODO have ready class-symbols for the by-ref versions of built-in valuetypes
    case _ => abort("cannot obtain a managed pointer for " + tk)
  }

}
