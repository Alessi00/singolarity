React = require 'react'
Glyphicon = React.createClass

    render: ->
        className = classNames 'glyphicon', "glyphicon-#{@props.iconClass}"
        <span className=className aria-hidden='true' />

module.exports = Glyphicon
