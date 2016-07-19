package openmods.calc.types.multi;

import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import gnu.trove.set.TCharSet;
import gnu.trove.set.hash.TCharHashSet;
import java.math.BigInteger;
import java.util.List;
import java.util.Set;
import openmods.calc.BinaryOperator;
import openmods.calc.Calculator;
import openmods.calc.Constant;
import openmods.calc.GenericFunctions;
import openmods.calc.OperatorDictionary;
import openmods.calc.UnaryFunction;
import openmods.calc.UnaryOperator;
import openmods.calc.parsing.DefaultExprNodeFactory;
import openmods.calc.parsing.IExprNodeFactory;
import openmods.calc.parsing.IValueParser;
import openmods.calc.parsing.StringEscaper;
import openmods.calc.parsing.Token;
import openmods.calc.types.bigint.BigIntPrinter;
import openmods.calc.types.fp.DoublePrinter;
import openmods.calc.types.multi.TypeDomain.Coercion;
import openmods.calc.types.multi.TypeDomain.ITruthEvaluator;
import openmods.calc.types.multi.TypedFunction.DispatchArg;
import openmods.calc.types.multi.TypedFunction.OptionalArgs;
import openmods.calc.types.multi.TypedFunction.RawDispatchArg;
import openmods.calc.types.multi.TypedFunction.RawReturn;
import openmods.calc.types.multi.TypedFunction.Variant;
import openmods.config.simpler.Configurable;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;

public class TypedValueCalculator extends Calculator<TypedValue> {

	private static final String SYMBOL_NULL = "null";
	private static final String SYMBOL_FALSE = "true";
	private static final String SYMBOL_TRUE = "false";

	public static class UnitType implements Comparable<UnitType> {
		public static final UnitType INSTANCE = new UnitType();

		private UnitType() {}

		@Override
		public String toString() {
			return "<null>";
		}

		@Override
		public int compareTo(UnitType o) {
			return 0; // always equal
		}
	}

	private static final TCharSet UNESCAPED_CHARS = new TCharHashSet(new char[] { '\'' });

	@Configurable
	public int base = 10;

	@Configurable
	public boolean uniformBaseNotation = false;

	@Configurable
	public boolean allowStandardPrinter = false;

	@Configurable
	public boolean escapeStrings = true;

	@Configurable
	public boolean numericBool = false;

	@Configurable
	public boolean printTypes = true;

	private final DoublePrinter doublePrinter = new DoublePrinter(8);

	private final BigIntPrinter bigIntPrinter = new BigIntPrinter();

	public TypedValueCalculator(IValueParser<TypedValue> parser, TypedValue nullValue, OperatorDictionary<TypedValue> operators, IExprNodeFactory<TypedValue> exprNodeFactory) {
		super(parser, nullValue, operators, exprNodeFactory);
	}

	@Override
	public String toString(TypedValue value) {
		final String contents;
		if (value.type == Double.class) contents = printDouble(value.unwrap(Double.class));
		else if (value.type == BigInteger.class) contents = printBigInteger(value.unwrap(BigInteger.class));
		else if (value.type == String.class) contents = printString(value.unwrap(String.class));
		else if (value.type == Boolean.class) contents = printBoolean(value.unwrap(Boolean.class));
		else if (value.type == UnitType.class) contents = SYMBOL_NULL;
		else contents = value.value.toString();

		return printTypes? "(" + value.type + ")" + contents : contents;
	}

	private String printBoolean(boolean value) {
		return numericBool? (value? "1" : "0") : (value? SYMBOL_FALSE : SYMBOL_TRUE);
	}

	private String printString(String value) {
		return escapeStrings? StringEscaper.escapeString(value, '"', UNESCAPED_CHARS) : value;
	}

	private String printBigInteger(BigInteger value) {
		if (base < Character.MIN_RADIX) return "invalid radix";
		return decorateBase(!uniformBaseNotation, base, (base <= Character.MAX_RADIX)? value.toString(base) : bigIntPrinter.toString(value, base));
	}

	private String printDouble(Double value) {
		if (base == 10 && !allowStandardPrinter && !uniformBaseNotation) {
			return value.toString();
		} else {
			if (value.isNaN()) return "NaN";
			if (value.isInfinite()) return value > 0? "+Inf" : "-Inf";
			final String result = doublePrinter.toString(value, base);
			return decorateBase(!uniformBaseNotation, base, result);
		}
	}

	private static final Function<BigInteger, Integer> INT_UNWRAP = new Function<BigInteger, Integer>() {
		@Override
		public Integer apply(BigInteger input) {
			return input.intValue();
		}
	};

	private static final int MAX_PRIO = 6;

	private interface CompareResultInterpreter {
		public boolean interpret(int value);
	}

	private static <T extends Comparable<T>> TypedBinaryOperator.ISimpleCoercedOperation<T, Boolean> createCompareOperation(final CompareResultInterpreter interpreter) {
		return new TypedBinaryOperator.ISimpleCoercedOperation<T, Boolean>() {
			@Override
			public Boolean apply(T left, T right) {
				return interpreter.interpret(left.compareTo(right));
			}
		};
	}

	private interface VariantCompareInterpreter {
		public TypedValue interpret(TypeDomain domain, int value);
	}

	private static <T extends Comparable<T>> TypedBinaryOperator.ICoercedOperation<T> createCompareOperation(final VariantCompareInterpreter interpreter) {
		return new TypedBinaryOperator.ICoercedOperation<T>() {
			@Override
			public TypedValue apply(TypeDomain domain, T left, T right) {
				return interpreter.interpret(domain, left.compareTo(right));
			}
		};
	}

	private static TypedBinaryOperator createCompareOperator(TypeDomain domain, String id, int priority, final CompareResultInterpreter compareTranslator) {
		return new TypedBinaryOperator.Builder(id, priority)
				.registerOperation(BigInteger.class, Boolean.class, TypedValueCalculator.<BigInteger> createCompareOperation(compareTranslator))
				.registerOperation(Double.class, Boolean.class, TypedValueCalculator.<Double> createCompareOperation(compareTranslator))
				.registerOperation(String.class, Boolean.class, TypedValueCalculator.<String> createCompareOperation(compareTranslator))
				.registerOperation(Boolean.class, Boolean.class, TypedValueCalculator.<Boolean> createCompareOperation(compareTranslator))
				.registerOperation(UnitType.class, Boolean.class, TypedValueCalculator.<UnitType> createCompareOperation(compareTranslator))
				.build(domain);
	}

	private static TypedUnaryOperator createUnaryNegation(String id, TypeDomain domain) {
		return new TypedUnaryOperator.Builder(id)
				.registerOperation(new TypedUnaryOperator.ISimpleOperation<BigInteger, BigInteger>() {
					@Override
					public BigInteger apply(BigInteger value) {
						return value.negate();
					}
				})
				.registerOperation(new TypedUnaryOperator.ISimpleOperation<Boolean, BigInteger>() {
					@Override
					public BigInteger apply(Boolean value) {
						return value? BigInteger.valueOf(-1) : BigInteger.ZERO;
					}
				})
				.registerOperation(new TypedUnaryOperator.ISimpleOperation<Double, Double>() {
					@Override
					public Double apply(Double value) {
						return -value;
					}

				})
				.build(domain);
	}

	private static final Set<Class<?>> NUMBER_TYPES = ImmutableSet.<Class<?>> of(Double.class, Boolean.class, BigInteger.class);

	public static TypedValueCalculator create() {
		final TypeDomain domain = new TypeDomain();

		domain.registerType(UnitType.class, "<null>");
		domain.registerType(BigInteger.class, "int");
		domain.registerType(Double.class, "float");
		domain.registerType(Boolean.class, "bool");
		domain.registerType(String.class, "str");

		domain.registerConverter(new IConverter<BigInteger, Double>() {
			@Override
			public Double convert(BigInteger value) {
				return value.doubleValue();
			}
		});
		domain.registerConverter(new IConverter<Boolean, BigInteger>() {
			@Override
			public BigInteger convert(Boolean value) {
				return value? BigInteger.ONE : BigInteger.ZERO;
			}
		});
		domain.registerConverter(new IConverter<Boolean, Double>() {
			@Override
			public Double convert(Boolean value) {
				return value? 1.0 : 0.0;
			}
		});

		domain.registerSymmetricCoercionRule(BigInteger.class, Double.class, Coercion.TO_RIGHT);
		domain.registerSymmetricCoercionRule(Boolean.class, Double.class, Coercion.TO_RIGHT);
		domain.registerSymmetricCoercionRule(Boolean.class, BigInteger.class, Coercion.TO_RIGHT);

		domain.registerTruthEvaluator(new ITruthEvaluator<Boolean>() {
			@Override
			public boolean isTruthy(Boolean value) {
				return value.booleanValue();
			}
		});

		domain.registerTruthEvaluator(new ITruthEvaluator<BigInteger>() {
			@Override
			public boolean isTruthy(BigInteger value) {
				return !value.equals(BigInteger.ZERO);
			}
		});

		domain.registerTruthEvaluator(new ITruthEvaluator<Double>() {
			@Override
			public boolean isTruthy(Double value) {
				return value != 0;
			}
		});

		domain.registerTruthEvaluator(new ITruthEvaluator<UnitType>() {
			@Override
			public boolean isTruthy(UnitType value) {
				return false;
			}
		});

		domain.registerTruthEvaluator(new ITruthEvaluator<String>() {
			@Override
			public boolean isTruthy(String value) {
				return !value.isEmpty();
			}
		});

		final TypedValue nullValue = domain.create(UnitType.class, UnitType.INSTANCE);

		final OperatorDictionary<TypedValue> operators = new OperatorDictionary<TypedValue>();

		// arithmetic
		operators.registerBinaryOperator(new TypedBinaryOperator.Builder("+", MAX_PRIO - 2)
				.registerOperation(new TypedBinaryOperator.ISimpleCoercedOperation<BigInteger, BigInteger>() {
					@Override
					public BigInteger apply(BigInteger left, BigInteger right) {
						return left.add(right);
					}
				}).registerOperation(new TypedBinaryOperator.ISimpleCoercedOperation<Double, Double>() {
					@Override
					public Double apply(Double left, Double right) {
						return left + right;
					}
				})
				.registerOperation(new TypedBinaryOperator.ISimpleCoercedOperation<String, String>() {
					@Override
					public String apply(String left, String right) {
						return left + right;
					}
				}).registerOperation(new TypedBinaryOperator.ISimpleCoercedOperation<Boolean, BigInteger>() {

					@Override
					public BigInteger apply(Boolean left, Boolean right) {
						return BigInteger.valueOf((left? 1 : 0) + (right? 1 : 0));
					}
				})
				.build(domain));

		operators.registerUnaryOperator(new UnaryOperator<TypedValue>("+") {
			@Override
			protected TypedValue execute(TypedValue value) {
				Preconditions.checkState(Number.class.isAssignableFrom(value.type) || Boolean.class.isAssignableFrom(value.type),
						"Not a number: %s", value);
				return value;
			}
		});

		operators.registerBinaryOperator(new TypedBinaryOperator.Builder("-", MAX_PRIO - 2)
				.registerOperation(new TypedBinaryOperator.ISimpleCoercedOperation<BigInteger, BigInteger>() {
					@Override
					public BigInteger apply(BigInteger left, BigInteger right) {
						return left.subtract(right);
					}
				}).registerOperation(new TypedBinaryOperator.ISimpleCoercedOperation<Double, Double>() {
					@Override
					public Double apply(Double left, Double right) {
						return left - right;
					}
				})
				.registerOperation(new TypedBinaryOperator.ISimpleCoercedOperation<Boolean, BigInteger>() {
					@Override
					public BigInteger apply(Boolean left, Boolean right) {
						return BigInteger.valueOf((left? 1 : 0) - (right? 1 : 0));
					}
				})
				.build(domain));

		operators.registerUnaryOperator(createUnaryNegation("-", domain));

		operators.registerUnaryOperator(createUnaryNegation("neg", domain));

		operators.registerBinaryOperator(new TypedBinaryOperator.Builder("*", MAX_PRIO - 1)
				.registerOperation(new TypedBinaryOperator.ISimpleCoercedOperation<BigInteger, BigInteger>() {
					@Override
					public BigInteger apply(BigInteger left, BigInteger right) {
						return left.multiply(right);
					}
				}).registerOperation(new TypedBinaryOperator.ISimpleCoercedOperation<Double, Double>() {
					@Override
					public Double apply(Double left, Double right) {
						return left * right;
					}
				})
				.registerOperation(new TypedBinaryOperator.ISimpleCoercedOperation<Boolean, BigInteger>() {
					@Override
					public BigInteger apply(Boolean left, Boolean right) {
						return BigInteger.valueOf((left? 1 : 0) * (right? 1 : 0));
					}
				})
				.registerOperation(new TypedBinaryOperator.ISimpleVariantOperation<String, BigInteger, String>() {

					@Override
					public String apply(String left, BigInteger right) {
						return StringUtils.repeat(left, right.intValue());
					}
				})
				.build(domain)).setDefault();

		operators.registerBinaryOperator(new TypedBinaryOperator.Builder("/", MAX_PRIO - 1)
				.registerOperation(new TypedBinaryOperator.ISimpleCoercedOperation<Double, Double>() {
					@Override
					public Double apply(Double left, Double right) {
						return left / right;
					}
				})
				.registerOperation(new TypedBinaryOperator.ISimpleCoercedOperation<BigInteger, Double>() {
					@Override
					public Double apply(BigInteger left, BigInteger right) {
						return left.doubleValue() / right.doubleValue();
					}
				})
				.registerOperation(new TypedBinaryOperator.ISimpleCoercedOperation<Boolean, Double>() {
					@Override
					public Double apply(Boolean left, Boolean right) {
						return (left? 1.0 : 0.0) / (right? 1.0 : 0.0);
					}
				})
				.build(domain));

		operators.registerBinaryOperator(new TypedBinaryOperator.Builder("%", MAX_PRIO - 1)
				.registerOperation(new TypedBinaryOperator.ISimpleCoercedOperation<Boolean, BigInteger>() {
					@Override
					public BigInteger apply(Boolean left, Boolean right) {
						return BigInteger.valueOf((left? 1 : 0) % (right? 1 : 0));
					}
				})
				.registerOperation(new TypedBinaryOperator.ISimpleCoercedOperation<BigInteger, BigInteger>() {
					@Override
					public BigInteger apply(BigInteger left, BigInteger right) {
						return left.mod(right);
					}
				})
				.registerOperation(new TypedBinaryOperator.ISimpleCoercedOperation<Double, Double>() {
					@Override
					public Double apply(Double left, Double right) {
						return left % right;
					}
				})
				.build(domain));

		operators.registerBinaryOperator(new TypedBinaryOperator.Builder("//", MAX_PRIO - 1)
				.registerOperation(new TypedBinaryOperator.ISimpleCoercedOperation<Boolean, BigInteger>() {
					@Override
					public BigInteger apply(Boolean left, Boolean right) {
						return BigInteger.valueOf((left? 1 : 0) / (right? 1 : 0));
					}
				})
				.registerOperation(new TypedBinaryOperator.ISimpleCoercedOperation<BigInteger, BigInteger>() {
					@Override
					public BigInteger apply(BigInteger left, BigInteger right) {
						return left.divide(right);
					}
				})
				.registerOperation(new TypedBinaryOperator.ISimpleCoercedOperation<Double, Double>() {
					@Override
					public Double apply(Double left, Double right) {
						return Math.floor(left / right);
					}
				})
				.build(domain));

		operators.registerBinaryOperator(new TypedBinaryOperator.Builder("**", MAX_PRIO)
				.registerOperation(new TypedBinaryOperator.ISimpleCoercedOperation<Boolean, BigInteger>() {
					@Override
					public BigInteger apply(Boolean left, Boolean right) {
						return BigInteger.valueOf((left? 0 : 1) * (right? 0 : 1));
					}
				})
				.registerOperation(new TypedBinaryOperator.ICoercedOperation<BigInteger>() {
					@Override
					public TypedValue apply(TypeDomain domain, BigInteger left, BigInteger right) {
						final int exp = right.intValue();
						return exp >= 0? domain.create(BigInteger.class, left.pow(exp)) : domain.create(Double.class, Math.pow(left.doubleValue(), exp));
					}
				})
				.registerOperation(new TypedBinaryOperator.ISimpleCoercedOperation<Double, Double>() {
					@Override
					public Double apply(Double left, Double right) {
						return Math.pow(left, right);
					}
				})
				.build(domain));

		// logic

		operators.registerUnaryOperator(new UnaryOperator<TypedValue>("!") {
			@Override
			protected TypedValue execute(TypedValue value) {
				final Optional<Boolean> isTruthy = value.isTruthy();
				Preconditions.checkState(isTruthy.isPresent(), "Can't determine truth value for %s", value);
				return value.domain.create(Boolean.class, !isTruthy.get());
			}
		});

		operators.registerBinaryOperator(new TypedBinaryBooleanOperator("&&", MAX_PRIO - 6) {
			@Override
			protected TypedValue execute(boolean isTruthy, TypedValue left, TypedValue right) {
				return isTruthy? right : left;
			}
		});

		operators.registerBinaryOperator(new TypedBinaryBooleanOperator("||", MAX_PRIO - 6) {
			@Override
			protected TypedValue execute(boolean isTruthy, TypedValue left, TypedValue right) {
				return isTruthy? left : right;
			}
		});

		operators.registerBinaryOperator(new BinaryOperator<TypedValue>("^^", MAX_PRIO - 6) {
			@Override
			protected TypedValue execute(TypedValue left, TypedValue right) {
				Preconditions.checkArgument(left.domain == right.domain, "Values from different domains: %s, %s", left, right);
				final Optional<Boolean> isLeftTruthy = left.isTruthy();
				Preconditions.checkState(isLeftTruthy.isPresent(), "Can't determine truth value for %s", left);

				final Optional<Boolean> isRightTruthy = right.isTruthy();
				Preconditions.checkState(isRightTruthy.isPresent(), "Can't determine truth value for %s", right);
				return left.domain.create(Boolean.class, isLeftTruthy.get() ^ isRightTruthy.get());
			}
		});

		// bitwise

		operators.registerUnaryOperator(new TypedUnaryOperator.Builder("~")
				.registerOperation(new TypedUnaryOperator.ISimpleOperation<Boolean, BigInteger>() {
					@Override
					public BigInteger apply(Boolean value) {
						return value? BigInteger.ZERO : BigInteger.ONE;
					}
				})
				.registerOperation(new TypedUnaryOperator.ISimpleOperation<BigInteger, BigInteger>() {
					@Override
					public BigInteger apply(BigInteger value) {
						return value.not();
					}
				})
				.build(domain));

		operators.registerBinaryOperator(new TypedBinaryOperator.Builder("&", MAX_PRIO - 4)
				.registerOperation(new TypedBinaryOperator.ISimpleCoercedOperation<Boolean, BigInteger>() {
					@Override
					public BigInteger apply(Boolean left, Boolean right) {
						return (left & right)? BigInteger.ONE : BigInteger.ZERO;
					}
				})
				.registerOperation(new TypedBinaryOperator.ISimpleCoercedOperation<BigInteger, BigInteger>() {
					@Override
					public BigInteger apply(BigInteger left, BigInteger right) {
						return left.and(right);
					}
				})
				.build(domain));

		operators.registerBinaryOperator(new TypedBinaryOperator.Builder("|", MAX_PRIO - 4)
				.registerOperation(new TypedBinaryOperator.ISimpleCoercedOperation<Boolean, BigInteger>() {
					@Override
					public BigInteger apply(Boolean left, Boolean right) {
						return (left | right)? BigInteger.ONE : BigInteger.ZERO;
					}
				})
				.registerOperation(new TypedBinaryOperator.ISimpleCoercedOperation<BigInteger, BigInteger>() {
					@Override
					public BigInteger apply(BigInteger left, BigInteger right) {
						return left.or(right);
					}
				})
				.build(domain));

		operators.registerBinaryOperator(new TypedBinaryOperator.Builder("^", MAX_PRIO - 4)
				.registerOperation(new TypedBinaryOperator.ISimpleCoercedOperation<Boolean, BigInteger>() {
					@Override
					public BigInteger apply(Boolean left, Boolean right) {
						return (left ^ right)? BigInteger.ONE : BigInteger.ZERO;
					}
				})
				.registerOperation(new TypedBinaryOperator.ISimpleCoercedOperation<BigInteger, BigInteger>() {
					@Override
					public BigInteger apply(BigInteger left, BigInteger right) {
						return left.xor(right);
					}
				})
				.build(domain));

		operators.registerBinaryOperator(new TypedBinaryOperator.Builder("<<", MAX_PRIO - 3)
				.registerOperation(new TypedBinaryOperator.ISimpleCoercedOperation<Boolean, BigInteger>() {
					@Override
					public BigInteger apply(Boolean left, Boolean right) {
						return BigInteger.valueOf((left? 1 : 0) << (right? 1 : 0));
					}
				})
				.registerOperation(new TypedBinaryOperator.ISimpleCoercedOperation<BigInteger, BigInteger>() {
					@Override
					public BigInteger apply(BigInteger left, BigInteger right) {
						return left.shiftLeft(right.intValue());
					}
				})
				.build(domain));

		operators.registerBinaryOperator(new TypedBinaryOperator.Builder(">>", MAX_PRIO - 3)
				.registerOperation(new TypedBinaryOperator.ISimpleCoercedOperation<Boolean, BigInteger>() {
					@Override
					public BigInteger apply(Boolean left, Boolean right) {
						return BigInteger.valueOf((left? 1 : 0) >> (right? 1 : 0));
					}
				})
				.registerOperation(new TypedBinaryOperator.ISimpleCoercedOperation<BigInteger, BigInteger>() {
					@Override
					public BigInteger apply(BigInteger left, BigInteger right) {
						return left.shiftRight(right.intValue());
					}
				})
				.build(domain));

		// comparision

		operators.registerBinaryOperator(createCompareOperator(domain, "<", MAX_PRIO - 5, new CompareResultInterpreter() {
			@Override
			public boolean interpret(int value) {
				return value < 0;
			}
		}));

		operators.registerBinaryOperator(createCompareOperator(domain, ">", MAX_PRIO - 5, new CompareResultInterpreter() {
			@Override
			public boolean interpret(int value) {
				return value > 0;
			}
		}));

		operators.registerBinaryOperator(createCompareOperator(domain, "==", MAX_PRIO - 5, new CompareResultInterpreter() {
			@Override
			public boolean interpret(int value) {
				return value == 0;
			}
		}));

		operators.registerBinaryOperator(createCompareOperator(domain, "!=", MAX_PRIO - 5, new CompareResultInterpreter() {
			@Override
			public boolean interpret(int value) {
				return value != 0;
			}
		}));

		operators.registerBinaryOperator(createCompareOperator(domain, "<=", MAX_PRIO - 5, new CompareResultInterpreter() {
			@Override
			public boolean interpret(int value) {
				return value <= 0;
			}
		}));

		operators.registerBinaryOperator(createCompareOperator(domain, ">=", MAX_PRIO - 5, new CompareResultInterpreter() {
			@Override
			public boolean interpret(int value) {
				return value >= 0;
			}
		}));

		{
			final VariantCompareInterpreter compareResultTranslator = new VariantCompareInterpreter() {
				@Override
				public TypedValue interpret(TypeDomain domain, int value) {
					return domain.create(BigInteger.class, BigInteger.valueOf(value));
				}
			};

			operators.registerBinaryOperator(new TypedBinaryOperator.Builder("<=>", MAX_PRIO - 5)
					.registerOperation(BigInteger.class, TypedValueCalculator.<BigInteger> createCompareOperation(compareResultTranslator))
					.registerOperation(Double.class, TypedValueCalculator.<Double> createCompareOperation(compareResultTranslator))
					.registerOperation(String.class, TypedValueCalculator.<String> createCompareOperation(compareResultTranslator))
					.registerOperation(Boolean.class, TypedValueCalculator.<Boolean> createCompareOperation(compareResultTranslator))
					.registerOperation(UnitType.class, TypedValueCalculator.<UnitType> createCompareOperation(compareResultTranslator))
					.build(domain));
		}

		final TypedValueParser valueParser = new TypedValueParser(domain);
		final IExprNodeFactory<TypedValue> exprNodeFactory = new DefaultExprNodeFactory<TypedValue>();
		final TypedValueCalculator result = new TypedValueCalculator(valueParser, nullValue, operators, exprNodeFactory);

		GenericFunctions.createStackManipulationFunctions(result);

		result.setGlobalSymbol(SYMBOL_NULL, Constant.create(nullValue));

		result.setGlobalSymbol(SYMBOL_FALSE, Constant.create(domain.create(Boolean.class, Boolean.TRUE)));
		result.setGlobalSymbol(SYMBOL_TRUE, Constant.create(domain.create(Boolean.class, Boolean.FALSE)));

		result.setGlobalSymbol("E", Constant.create(domain.create(Double.class, Math.E)));
		result.setGlobalSymbol("PI", Constant.create(domain.create(Double.class, Math.PI)));
		result.setGlobalSymbol("NAN", Constant.create(domain.create(Double.class, Double.NaN)));
		result.setGlobalSymbol("INF", Constant.create(domain.create(Double.class, Double.POSITIVE_INFINITY)));

		class PredicateIsType extends UnaryFunction<TypedValue> {
			private final Class<?> cls;

			public PredicateIsType(Class<?> cls) {
				domain.checkIsKnownType(cls);
				this.cls = cls;
			}

			@Override
			protected TypedValue execute(TypedValue value) {
				return value.domain.create(Boolean.class, value.is(cls));
			}
		}

		result.setGlobalSymbol("isint", new PredicateIsType(BigInteger.class));
		result.setGlobalSymbol("isbool", new PredicateIsType(Boolean.class));
		result.setGlobalSymbol("isfloat", new PredicateIsType(Double.class));
		result.setGlobalSymbol("isnull", new PredicateIsType(UnitType.class));
		result.setGlobalSymbol("isstr", new PredicateIsType(String.class));

		result.setGlobalSymbol("isnumber", new UnaryFunction<TypedValue>() {
			@Override
			protected TypedValue execute(TypedValue value) {
				return value.domain.create(Boolean.class, NUMBER_TYPES.contains(value.type));
			}
		});

		result.setGlobalSymbol("type", new UnaryFunction<TypedValue>() {
			@Override
			protected TypedValue execute(TypedValue value) {
				final TypeDomain domain = value.domain;
				return domain.create(String.class, domain.getName(value.type));
			}
		});

		result.setGlobalSymbol("bool", new UnaryFunction<TypedValue>() {
			@Override
			protected TypedValue execute(TypedValue value) {
				final Optional<Boolean> isTruthy = value.isTruthy();
				Preconditions.checkArgument(isTruthy.isPresent(), "Cannot determine value of %s", value);
				return value.domain.create(Boolean.class, isTruthy.get());
			}
		});

		result.setGlobalSymbol("str", new UnaryFunction<TypedValue>() {
			@Override
			protected TypedValue execute(TypedValue value) {
				if (value.is(String.class)) return value;
				else return value.domain.create(String.class, result.toString(value));
			}
		});

		result.setGlobalSymbol("int", new SimpleTypedFunction(domain) {
			@Variant
			public BigInteger convert(@DispatchArg(extra = { Boolean.class }) BigInteger value) {
				return value;
			}

			@Variant
			public BigInteger convert(@DispatchArg Double value) {
				return BigInteger.valueOf(value.longValue());
			}

			@Variant
			public BigInteger convert(@DispatchArg String value, @OptionalArgs Optional<BigInteger> radix) {
				final int usedRadix = radix.transform(INT_UNWRAP).or(result.base);
				final Pair<BigInteger, Double> result = TypedValueParser.NUMBER_PARSER.parseString(value, usedRadix);
				Preconditions.checkArgument(result.getRight() == null, "Fractional part in argument to 'int': %s", value);
				return result.getLeft();
			}
		});

		result.setGlobalSymbol("float", new SimpleTypedFunction(domain) {
			@Variant
			public Double convert(@DispatchArg(extra = { BigInteger.class, Boolean.class }) Double value) {
				return value;
			}

			@Variant
			public Double convert(@DispatchArg String value, @OptionalArgs Optional<BigInteger> radix) {
				final int usedRadix = radix.transform(INT_UNWRAP).or(result.base);
				final Pair<BigInteger, Double> result = TypedValueParser.NUMBER_PARSER.parseString(value, usedRadix);
				return result.getLeft().doubleValue() + result.getRight();
			}
		});

		result.setGlobalSymbol("number", new SimpleTypedFunction(domain) {
			@Variant
			@RawReturn
			public TypedValue convert(@RawDispatchArg({ Boolean.class, BigInteger.class, Double.class }) TypedValue value) {
				return value;
			}

			@Variant
			@RawReturn
			public TypedValue convert(@DispatchArg String value, @OptionalArgs Optional<BigInteger> radix) {
				final int usedRadix = radix.transform(INT_UNWRAP).or(result.base);
				final Pair<BigInteger, Double> result = TypedValueParser.NUMBER_PARSER.parseString(value, usedRadix);
				return TypedValueParser.mergeNumberParts(domain, result);
			}
		});

		result.setGlobalSymbol("parse", new SimpleTypedFunction(domain) {
			@Variant
			@RawReturn
			public TypedValue parse(String value) {
				try {
					final List<Token> tokens = Lists.newArrayList(result.tokenize(value));
					Preconditions.checkState(tokens.size() == 1, "Expected single token from '%', got %s", value, tokens.size());
					return valueParser.parseToken(tokens.get(0));
				} catch (Exception e) {
					throw new IllegalArgumentException("Failed to parse '" + value + "'", e);
				}
			}
		});

		result.setGlobalSymbol("isnan", new SimpleTypedFunction(domain) {
			@Variant
			public Boolean isNan(Double v) {
				return v.isNaN();
			}
		});

		result.setGlobalSymbol("isinf", new SimpleTypedFunction(domain) {
			@Variant
			public Boolean isInf(Double v) {
				return v.isInfinite();
			}
		});

		result.setGlobalSymbol("abs", new SimpleTypedFunction(domain) {
			@Variant
			public Boolean abs(@DispatchArg Boolean v) {
				return v;
			}

			@Variant
			public BigInteger abs(@DispatchArg BigInteger v) {
				return v.abs();
			}

			@Variant
			public Double abs(@DispatchArg Double v) {
				return Math.abs(v);
			}
		});

		result.setGlobalSymbol("sqrt", new SimpleTypedFunction(domain) {
			@Variant
			public Double sqrt(Double v) {
				return Math.sqrt(v);
			}
		});

		result.setGlobalSymbol("floor", new SimpleTypedFunction(domain) {
			@Variant
			@RawReturn
			public TypedValue floor(@RawDispatchArg({ BigInteger.class, Boolean.class }) TypedValue v) {
				return v;
			}

			@Variant
			public Double floor(@DispatchArg Double v) {
				return Math.floor(v);
			}
		});

		result.setGlobalSymbol("ceil", new SimpleTypedFunction(domain) {
			@Variant
			@RawReturn
			public TypedValue ceil(@RawDispatchArg({ BigInteger.class, Boolean.class }) TypedValue v) {
				return v;
			}

			@Variant
			public Double ceil(@DispatchArg Double v) {
				return Math.ceil(v);
			}
		});

		result.setGlobalSymbol("cos", new SimpleTypedFunction(domain) {
			@Variant
			public Double cos(Double v) {
				return Math.cos(v);
			}
		});

		result.setGlobalSymbol("cosh", new SimpleTypedFunction(domain) {
			@Variant
			public Double cosh(Double v) {
				return Math.cosh(v);
			}
		});

		result.setGlobalSymbol("acos", new SimpleTypedFunction(domain) {
			@Variant
			public Double acos(Double v) {
				return Math.acos(v);
			}
		});

		result.setGlobalSymbol("acosh", new SimpleTypedFunction(domain) {
			@Variant
			public Double acosh(Double v) {
				return Math.log(v + Math.sqrt(v * v - 1));
			}
		});

		result.setGlobalSymbol("sin", new SimpleTypedFunction(domain) {
			@Variant
			public Double sin(Double v) {
				return Math.sin(v);
			}
		});

		result.setGlobalSymbol("sinh", new SimpleTypedFunction(domain) {
			@Variant
			public Double sinh(Double v) {
				return Math.sinh(v);
			}
		});

		result.setGlobalSymbol("asin", new SimpleTypedFunction(domain) {
			@Variant
			public Double asin(Double v) {
				return Math.asin(v);
			}
		});

		result.setGlobalSymbol("asinh", new SimpleTypedFunction(domain) {
			@Variant
			public Double asinh(Double v) {
				return v.isInfinite()? v : Math.log(v + Math.sqrt(v * v + 1));
			}
		});

		result.setGlobalSymbol("tan", new SimpleTypedFunction(domain) {
			@Variant
			public Double tan(Double v) {
				return Math.tan(v);
			}
		});

		result.setGlobalSymbol("atan", new SimpleTypedFunction(domain) {
			@Variant
			public Double atan(Double v) {
				return Math.atan(v);
			}
		});

		result.setGlobalSymbol("atan2", new SimpleTypedFunction(domain) {
			@Variant
			public Double atan2(Double x, Double y) {
				return Math.atan2(x, y);
			}
		});

		result.setGlobalSymbol("tanh", new SimpleTypedFunction(domain) {
			@Variant
			public Double tanh(Double v) {
				return Math.tanh(v);
			}
		});

		result.setGlobalSymbol("atanh", new SimpleTypedFunction(domain) {
			@Variant
			public Double atanh(Double v) {
				return Math.log((1 + v) / (1 - v)) / 2;
			}
		});

		result.setGlobalSymbol("ln", new SimpleTypedFunction(domain) {
			@Variant
			public Double ln(Double v) {
				return Math.log(v);
			}
		});

		result.setGlobalSymbol("log", new SimpleTypedFunction(domain) {
			@Variant
			public Double log(Double v, @OptionalArgs Optional<Double> base) {
				if (base.isPresent()) {
					return Math.log(v) / Math.log(base.get());
				} else {
					return Math.log10(v);
				}
			}
		});

		result.setGlobalSymbol("sgn", new SimpleTypedFunction(domain) {
			@Variant
			public BigInteger sgn(@DispatchArg(extra = { Boolean.class }) BigInteger v) {
				return BigInteger.valueOf(v.signum());
			}

			@Variant
			public Double sgn(@DispatchArg Double v) {
				return Math.signum(v);
			}
		});

		result.setGlobalSymbol("rad", new SimpleTypedFunction(domain) {
			@Variant
			public Double rad(Double v) {
				return Math.toRadians(v);
			}
		});

		result.setGlobalSymbol("deg", new SimpleTypedFunction(domain) {
			@Variant
			public Double deg(Double v) {
				return Math.toDegrees(v);
			}
		});

		result.setGlobalSymbol("modpow", new SimpleTypedFunction(domain) {
			@Variant
			public BigInteger modpow(BigInteger v, BigInteger exp, BigInteger mod) {
				return v.modPow(exp, mod);
			}
		});

		result.setGlobalSymbol("gcd", new SimpleTypedFunction(domain) {
			@Variant
			public BigInteger gcd(BigInteger v1, BigInteger v2) {
				return v1.gcd(v2);
			}
		});

		return result;
	}
}
