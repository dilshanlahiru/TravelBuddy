module.exports = [
  {
    files: ["**/*.js"],
    languageOptions: {
      ecmaVersion: 2022,
      sourceType: "commonjs",
      globals: {
        fetch: "readonly",
        require: "readonly",
        exports: "readonly",
        module: "readonly",
        console: "readonly",
        __dirname: "readonly",
        AbortSignal: "readonly",
      },
    },
    rules: {
      "no-unused-vars": "error",
      "no-undef": "error",
    },
  },
];
