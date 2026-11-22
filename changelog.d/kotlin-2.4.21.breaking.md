- Built with Kotlin 2.4.21. On the JVM, constructors that take a value class (`NodeId` and others) gained a
  `BoxingConstructorMarker` in their synthetic signatures, so a consumer compiled against an earlier release must
  recompile; source is unchanged.
