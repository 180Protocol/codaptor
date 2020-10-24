const path = require('path');
const HtmlWebpackPlugin = require('html-webpack-plugin');
const CleanWebpackPlugin = require('clean-webpack-plugin');
const CopyWebpackPlugin = require('copy-webpack-plugin');

const outputPath = path.resolve(__dirname, 'dist');

module.exports = {
  mode: 'production',
  entry: {
    swaggerui: './src/index.js',
  },
  module: {
    rules: [
      {
        test: /\.config$/,
        use: [
          {
            loader: 'file-loader',
            options: {
              name: '[name].config',
              emitFile: false,
            }
          }
        ]
      },
      {
        test: /\.css$/,
        use: [
          { loader: 'style-loader' },
          { loader: 'css-loader' },
        ]
      }
    ]
  },
  plugins: [
    new CleanWebpackPlugin([
      outputPath
    ]),
    new HtmlWebpackPlugin({
      template: 'index.html'
    })
  ],
  output: {
    filename: 'cordaptor.bundle.js',
    path: outputPath,
  }
};
