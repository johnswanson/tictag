module.exports = {
    plugins: [
        require('postcss-import')({root: 'node_modules'}),
        require('postcss-cssnext')(),
        require('cssnano')({autoprefixer: false})
    ]
}
