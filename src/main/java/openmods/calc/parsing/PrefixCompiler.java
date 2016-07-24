package openmods.calc.parsing;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import java.util.Iterator;
import java.util.List;
import openmods.calc.BinaryOperator;
import openmods.calc.BinaryOperator.Associativity;
import openmods.calc.OperatorDictionary;
import openmods.calc.UnaryOperator;

public class PrefixCompiler<E> extends AstCompiler<E> {

	private final IValueParser<E> valueParser;

	private final OperatorDictionary<E> operators;

	public PrefixCompiler(IValueParser<E> valueParser, OperatorDictionary<E> operators, IExprNodeFactory<E> exprNodeFactory) {
		super(exprNodeFactory);
		this.valueParser = valueParser;
		this.operators = operators;
	}

	protected IExprNode<E> parseNode(Token token, Iterator<Token> input, IExprNodeFactory<E> exprNodeFactory) {
		if (token.type.isValue()) {
			final E value = valueParser.parseToken(token);
			return exprNodeFactory.createValueNode(value);
		}

		if (token.type == TokenType.SYMBOL) {
			final ImmutableList<IExprNode<E>> emptyArgs = ImmutableList.of();
			return exprNodeFactory.createSymbolExprNodeFactory(token.value).createRootSymbolNode(emptyArgs);
		}

		if (token.type == TokenType.MODIFIER) return parseModifierNode(token.value, input, exprNodeFactory);

		if (token.type == TokenType.LEFT_BRACKET)
			return parseNestedNode(token.value, input, exprNodeFactory);

		throw new IllegalArgumentException("Unexpected token: " + token);
	}

	private IExprNode<E> parseNestedNode(String openingBracket, Iterator<Token> input, IExprNodeFactory<E> exprNodeFactory) {
		final String closingBracket = TokenUtils.getClosingBracket(openingBracket);

		if (openingBracket.equals("(")) {
			final Token operationToken = input.next();

			final String operationName = operationToken.value;
			if (operationToken.type == TokenType.SYMBOL) {
				final ISymbolExprNodeFactory<E> symbolNodeFactory = exprNodeFactory.createSymbolExprNodeFactory(operationName);
				final List<IExprNode<E>> args = collectArgs(openingBracket, closingBracket, input, symbolNodeFactory);
				return symbolNodeFactory.createRootSymbolNode(args);
				// no modifiers allowed on this position (yet), so assuming operator
			} else if (operationToken.type == TokenType.OPERATOR || operationToken.type == TokenType.MODIFIER) {
				final List<IExprNode<E>> args = collectArgs(openingBracket, closingBracket, input, exprNodeFactory);
				if (args.size() == 1) {
					final UnaryOperator<E> unaryOperator = operators.getUnaryOperator(operationName);
					Preconditions.checkState(unaryOperator != null, "Invalid unary operator '%s'", operationName);
					return exprNodeFactory.createUnaryOpNode(unaryOperator, args.get(0));
				} else if (args.size() > 1) {
					final BinaryOperator<E> binaryOperator = operators.getBinaryOperator(operationName);
					Preconditions.checkState(binaryOperator != null, "Invalid binary operator '%s'", operationName);
					return compileBinaryOpNode(binaryOperator, args, exprNodeFactory);
				} else {
					throw new IllegalArgumentException("Called operator " + operationName + " without any arguments");
				}
			} else {
				// TODO: consider doing nested statements on first entry?
				throw new IllegalArgumentException("Unexpected token: " + operationToken);
			}
		} else {
			// not parenthesis, so probably data structure
			final List<IExprNode<E>> args = collectArgs(openingBracket, closingBracket, input, exprNodeFactory);
			return exprNodeFactory.createBracketNode(openingBracket, closingBracket, args);
		}
	}

	private IExprNode<E> parseAnyNode(final Token token, Iterator<Token> input, IExprNodeFactory<E> exprNodeFactory) {
		return exprNodeFactory.getBehaviour().shouldQuote()? parseQuotedNode(token, input, exprNodeFactory) : parseNode(token, input, exprNodeFactory);
	}

	private List<IExprNode<E>> collectArgs(String openingBracket, String closingBracket, Iterator<Token> input, IExprNodeFactory<E> exprNodeFactory) {
		final List<IExprNode<E>> args = Lists.newArrayList();
		while (true) {
			final Token argToken = input.next();
			if (argToken.type.equals(TokenType.SEPARATOR)) {
				// comma is whitespace
			} else if (argToken.type.equals(TokenType.RIGHT_BRACKET)) {
				Preconditions.checkState(argToken.value.equals(closingBracket), "Unmatched brackets: '%s' and '%s'", openingBracket, argToken.value);
				break;
			} else {
				args.add(parseAnyNode(argToken, input, exprNodeFactory));
			}
		}
		return args;
	}

	private IExprNode<E> parseModifierNode(String modifier, Iterator<Token> input, IExprNodeFactory<E> exprNodeFactory) {
		final IModifierExprNodeFactory<E> newNodeFactory = exprNodeFactory.createModifierExprNodeFactory(modifier);
		final Token startToken = input.next();
		return newNodeFactory.createRootModifierNode(parseAnyNode(startToken, input, newNodeFactory));
	}

	private IExprNode<E> parseQuotedNode(Token start, Iterator<Token> input, final IExprNodeFactory<E> exprNodeFactory) {
		if (start.type == TokenType.LEFT_BRACKET) {
			return parseNestedQuotedNode(start.value, input, exprNodeFactory);
		} else if (start.type.isValue()) {
			final E value = valueParser.parseToken(start);
			return exprNodeFactory.createValueNode(value);
		} else {
			return exprNodeFactory.createRawValueNode(start);
		}
	}

	private IExprNode<E> parseNestedQuotedNode(String openingBracket, Iterator<Token> input, IExprNodeFactory<E> exprNodeFactory) {
		final List<IExprNode<E>> children = Lists.newArrayList();
		while (true) {
			final Token token = input.next();
			if (token.type == TokenType.RIGHT_BRACKET) {
				TokenUtils.checkIsValidBracketPair(openingBracket, token.value);
				return exprNodeFactory.createBracketNode(openingBracket, token.value, children);
			} else {
				children.add(parseQuotedNode(token, input, exprNodeFactory));
			}
		}
	}

	private IExprNode<E> compileBinaryOpNode(BinaryOperator<E> op, List<IExprNode<E>> args, IExprNodeFactory<E> exprNodeFactory) {
		if (op.associativity == Associativity.LEFT) {
			IExprNode<E> left = args.get(0);
			IExprNode<E> right = args.get(1);

			for (int i = 2; i < args.size(); i++) {
				left = exprNodeFactory.createBinaryOpNode(op, left, right);
				right = args.get(i);
			}

			return exprNodeFactory.createBinaryOpNode(op, left, right);
		} else {
			final int lastArg = args.size() - 1;
			IExprNode<E> left = args.get(lastArg - 1);
			IExprNode<E> right = args.get(lastArg);

			for (int i = lastArg - 2; i >= 0; i--) {
				right = exprNodeFactory.createBinaryOpNode(op, left, right);
				left = args.get(i);
			}

			return exprNodeFactory.createBinaryOpNode(op, left, right);
		}

	}

	@Override
	public IExprNode<E> compileAst(Iterable<Token> input, IExprNodeFactory<E> exprNodeFactory) {
		final Iterator<Token> tokens = input.iterator();
		final Token firstToken = tokens.next();
		final IExprNode<E> result = parseNode(firstToken, tokens, exprNodeFactory);
		if (tokens.hasNext())
			throw new IllegalStateException("Unconsumed tokens: " + Lists.newArrayList(tokens));
		return result;
	}

}
