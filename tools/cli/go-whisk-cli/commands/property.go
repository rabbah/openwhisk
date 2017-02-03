/*
 * Copyright 2015-2016 IBM Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package commands

import (
    "bufio"
    "errors"
    "fmt"
    "os"
    "strings"

    "github.com/mitchellh/go-homedir"
    "github.com/spf13/cobra"
    "github.com/fatih/color"

    "../../go-whisk/whisk"
    "../wski18n"
)

var Properties struct {
    Auth        string
    APIHost     string
    InsecureSSL bool
    APIVersion  string
    APIBuild    string
    APIBuildNo  string
    CLIVersion  string
    Namespace   string
    PropsFile   string
}

const DefaultAuth           string = ""
const DefaultInsecureSSL    bool = false
const DefaultAPIHost        string = ""
const DefaultAPIVersion     string = "v1"
const DefaultAPIBuild       string = ""
const DefaultAPIBuildNo     string = ""
const DefaultNamespace      string = "_"
const DefaultPropsFile      string = "~/.wskprops"

var propertyCmd = &cobra.Command{
    Use:   "property",
    Short: wski18n.T("work with whisk properties"),
}

//
// Set one or more openwhisk property values
//
var propertySetCmd = &cobra.Command{
    Use:            "set",
    Short:          wski18n.T("set property"),
    SilenceUsage:   true,
    SilenceErrors:  true,
    PreRunE: setupClientConfig,
    RunE: func(cmd *cobra.Command, args []string) error {
        var okMsg string = ""
        var werr *whisk.WskError = nil

        // get current props
        props, err := readProps(Properties.PropsFile)
        if err != nil {
            whisk.Debug(whisk.DbgError, "readProps(%s) failed: %s\n", Properties.PropsFile, err)
            errStr := fmt.Sprintf(
                wski18n.T("Unable to set the property value: {{.err}}",
                    map[string]interface{}{"err": err}))
            werr = whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return werr
        }

        // read in each flag, update if necessary

        if auth := flags.global.auth; len(auth) > 0 {
            props["AUTH"] = auth
            client.Config.AuthToken = auth
            okMsg += fmt.Sprintf(
                wski18n.T("{{.ok}} whisk auth set to {{.auth}}\n",
                    map[string]interface{}{"ok": color.GreenString("ok:"), "auth": boldString(auth)}))
        }

        if insecure := flags.property.insecureSet; len(insecure) > 0 {
            props["INSECURE_SSL"] = insecure
            client.Config.Insecure = strings.ToLower(insecure) == "yes" || strings.ToLower(insecure) == "true"
            okMsg += fmt.Sprintf(
                wski18n.T("{{.ok}} whisk insecure set to {{.insecure}}\n",
                    map[string]interface{}{"ok": color.GreenString("ok:"), "insecure": boldString(insecure)}))
        }

        if apiHost := flags.property.apihostSet; len(apiHost) > 0 {
            baseURL, err := getURLBase(apiHost)

            if err != nil {
                // Not aborting now.  Subsequent commands will result in error
                whisk.Debug(whisk.DbgError, "getURLBase(%s) error: %s", apiHost, err)
                errStr := fmt.Sprintf(
                    wski18n.T("Unable to set API host value; the API host value '{{.apihost}}' is invalid: {{.err}}",
                        map[string]interface{}{"apihost": apiHost, "err": err}))
                werr = whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL,
                    whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
            } else {
                props["APIHOST"] = apiHost
                client.Config.BaseURL = baseURL
                okMsg += fmt.Sprintf(
                    wski18n.T("{{.ok}} whisk API host set to {{.host}}\n",
                        map[string]interface{}{"ok": color.GreenString("ok:"), "host": boldString(apiHost)}))
            }
        }

        if apiVersion := flags.property.apiversionSet; len(apiVersion) > 0 {
            props["APIVERSION"] = apiVersion
            client.Config.Version = apiVersion
            okMsg += fmt.Sprintf(
                wski18n.T("{{.ok}} whisk API version set to {{.version}}\n",
                    map[string]interface{}{"ok": color.GreenString("ok:"), "version": boldString(apiVersion)}))
        }

        if namespace := flags.property.namespaceSet; len(namespace) > 0 {
            namespaces, _, err := client.Namespaces.List()
            if err != nil {
                whisk.Debug(whisk.DbgError, "client.Namespaces.List() failed: %s\n", err)
                errStr := fmt.Sprintf(
                    wski18n.T("Authenticated user does not have namespace '{{.name}}'; set command failed: {{.err}}",
                        map[string]interface{}{"name": namespace, "err": err}))
                werr = whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            } else {
                whisk.Debug(whisk.DbgInfo, "Validating namespace '%s' is in user namespace list %#v\n", namespace, namespaces)
                var validNamespace bool
                for _, ns := range namespaces {
                    if ns.Name == namespace {
                        whisk.Debug(whisk.DbgInfo, "Namespace '%s' is valid\n", namespace)
                        validNamespace = true
                    }
                }
                if !validNamespace {
                    whisk.Debug(whisk.DbgError, "Namespace '%s' is not in the list of entitled namespaces\n", namespace)
                    errStr := fmt.Sprintf(
                        wski18n.T("Namespace '{{.name}}' is not in the list of entitled namespaces",
                            map[string]interface{}{"name": namespace}))
                    werr = whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
                } else {
                    props["NAMESPACE"] = namespace
                    okMsg += fmt.Sprintf(
                        wski18n.T("{{.ok}} whisk namespace set to {{.name}}\n",
                            map[string]interface{}{"ok": color.GreenString("ok:"), "name": boldString(namespace)}))
                }
            }
        }

        err = writeProps(Properties.PropsFile, props)
        if err != nil {
            whisk.Debug(whisk.DbgError, "writeProps(%s, %#v) failed: %s\n", Properties.PropsFile, props, err)
            errStr := fmt.Sprintf(
                wski18n.T("Unable to set the property value(s): {{.err}}",
                    map[string]interface{}{"err": err}))
            werr = whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
        } else {
            fmt.Fprintf(color.Output, okMsg)
        }

        if (werr != nil) {
            return werr
        }

        return nil
    },
}

var propertyUnsetCmd = &cobra.Command{
    Use:            "unset",
    Short:          wski18n.T("unset property"),
    SilenceUsage:   true,
    SilenceErrors:  true,
    RunE: func(cmd *cobra.Command, args []string) error {
        var okMsg string = ""
        props, err := readProps(Properties.PropsFile)
        if err != nil {
            whisk.Debug(whisk.DbgError, "readProps(%s) failed: %s\n", Properties.PropsFile, err)
            errStr := fmt.Sprintf(
                wski18n.T("Unable to unset the property value: {{.err}}",
                    map[string]interface{}{"err": err}))
            werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return werr
        }

        // read in each flag, update if necessary

        if flags.property.auth {
            delete(props, "AUTH")
            okMsg += fmt.Sprintf(
                wski18n.T("{{.ok}} whisk auth unset",
                    map[string]interface{}{"ok": color.GreenString("ok:")}))
            if len(DefaultAuth) > 0 {
                okMsg += fmt.Sprintf(
                    wski18n.T("; the default value of {{.default}} will be used.\n",
                        map[string]interface{}{"default": boldString(DefaultAuth)}))
            } else {
                okMsg += fmt.Sprint(
                    wski18n.T("; there is no default value that can be used.\n"))
            }
        }

        if flags.property.namespace {
            delete(props, "NAMESPACE")
            okMsg += fmt.Sprintf(
                wski18n.T("{{.ok}} whisk namespace unset",
                    map[string]interface{}{"ok": color.GreenString("ok:")}))
            if len(DefaultNamespace) > 0 {
                okMsg += fmt.Sprintf(
                    wski18n.T("; the default value of {{.default}} will be used.\n",
                        map[string]interface{}{"default": boldString(DefaultNamespace)}))
            } else {
                okMsg += fmt.Sprint(
                    wski18n.T("; there is no default value that can be used.\n"))
            }
        }

        if flags.property.insecure {
            delete(props, "INSECURE_SSL")
            okMsg += fmt.Sprintf(
                wski18n.T("{{.ok}} whisk insecure unset",
                    map[string]interface{}{"ok": color.GreenString("ok:")}))
            okMsg += fmt.Sprintf(
                wski18n.T("; the default value of {{.default}} will be used.\n",
                    map[string]interface{}{"default": boldString(DefaultInsecureSSL)}))
        }

        if flags.property.apihost {
            delete(props, "APIHOST")
            okMsg += fmt.Sprintf(
                wski18n.T("{{.ok}} whisk API host unset",
                    map[string]interface{}{"ok": color.GreenString("ok:")}))
            if len(DefaultAPIHost) > 0 {
                okMsg += fmt.Sprintf(
                    wski18n.T("; the default value of {{.default}} will be used.\n",
                        map[string]interface{}{"default": boldString(DefaultAPIHost)}))
            } else {
                okMsg += fmt.Sprint(
                    wski18n.T("; there is no default value that can be used.\n"))
            }
        }

        if flags.property.apiversion {
            delete(props, "APIVERSION")
            okMsg += fmt.Sprintf(
                wski18n.T("{{.ok}} whisk API version unset",
                    map[string]interface{}{"ok": color.GreenString("ok:")}))
            if len(DefaultAPIVersion) > 0 {
                okMsg += fmt.Sprintf(
                    wski18n.T("; the default value of {{.default}} will be used.\n",
                        map[string]interface{}{"default": boldString(DefaultAPIVersion)}))
            } else {
                okMsg += fmt.Sprint(
                    wski18n.T("; there is no default value that can be used.\n"))
            }
        }

        err = writeProps(Properties.PropsFile, props)
        if err != nil {
            whisk.Debug(whisk.DbgError, "writeProps(%s, %#v) failed: %s\n", Properties.PropsFile, props, err)
            errStr := fmt.Sprintf(
                wski18n.T("Unable to unset the property value: {{.err}}",
                    map[string]interface{}{"err": err}))
            werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return werr
        }

        fmt.Fprintf(color.Output, okMsg)
        if err = loadProperties(); err != nil {
            whisk.Debug(whisk.DbgError, "loadProperties() failed: %s\n", err)
        }
        return nil
    },
}

var propertyGetCmd = &cobra.Command{
    Use:            "get",
    Short:          wski18n.T("get property"),
    SilenceUsage:   true,
    SilenceErrors:  true,
    PreRunE: setupClientConfig,
    RunE: func(cmd *cobra.Command, args []string) error {

        // If no property is explicitly specified, default to all properties
        if !(flags.property.all || flags.property.auth ||
             flags.property.apiversion || flags.property.cliversion ||
             flags.property.namespace || flags.property.apibuild ||
             flags.property.apihost || flags.property.apibuildno || flags.property.insecure) {
            flags.property.all = true
        }

        if flags.property.all || flags.property.auth {
            fmt.Fprintf(color.Output, "%s\t\t%s\n", wski18n.T("whisk auth"), boldString(Properties.Auth))
        }

        if flags.property.all || flags.property.apihost {
            fmt.Fprintf(color.Output, "%s\t\t%s\n", wski18n.T("whisk API host"), boldString(Properties.APIHost))
        }

        if flags.property.all || flags.property.insecure {
            fmt.Fprintf(color.Output, "%s\t\t%s\n", wski18n.T("insecure SSL"), boldString(Properties.InsecureSSL))
        }

        if flags.property.all || flags.property.apiversion {
            fmt.Fprintf(color.Output, "%s\t%s\n", wski18n.T("whisk API version"), boldString(Properties.APIVersion))
        }

        if flags.property.all || flags.property.namespace {
            fmt.Fprintf(color.Output, "%s\t\t%s\n", wski18n.T("whisk namespace"), boldString(Properties.Namespace))
        }

        if flags.property.all || flags.property.cliversion {
            fmt.Fprintf(color.Output, "%s\t%s\n", wski18n.T("whisk CLI version"), boldString(Properties.CLIVersion))
        }

        if flags.property.all || flags.property.apibuild || flags.property.apibuildno {
            info, _, err := client.Info.Get()
            if err != nil {
                whisk.Debug(whisk.DbgError, "client.Info.Get() failed: %s\n", err)
                info = &whisk.Info{}
                info.Build = wski18n.T("Unknown")
                info.BuildNo = wski18n.T("Unknown")
            }
            if flags.property.all || flags.property.apibuild {
                fmt.Fprintf(color.Output, "%s\t\t%s\n", wski18n.T("whisk API build"), boldString(info.Build))
            }
            if flags.property.all || flags.property.apibuildno {
                fmt.Fprintf(color.Output, "%s\t%s\n", wski18n.T("whisk API build number"), boldString(info.BuildNo))
            }
            if err != nil {
                errStr := fmt.Sprintf(
                    wski18n.T("Unable to obtain API build information: {{.err}}",
                        map[string]interface{}{"err": err}))
                werr := whisk.MakeWskErrorFromWskError(errors.New(errStr), err, whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
                return werr
            }
        }

        return nil
    },
}

func init() {
    propertyCmd.AddCommand(
        propertySetCmd,
        propertyUnsetCmd,
        propertyGetCmd,
    )

    // need to set property flags as booleans instead of strings... perhaps with boolApihost...
    propertyGetCmd.Flags().BoolVar(&flags.property.auth, "auth", false, wski18n.T("authorization key"))
    propertyGetCmd.Flags().BoolVar(&flags.property.apihost, "apihost", false, wski18n.T("whisk API host"))
    propertyGetCmd.Flags().BoolVar(&flags.property.insecure, "insecure", false, wski18n.T("insecure SSL"))
    propertyGetCmd.Flags().BoolVar(&flags.property.apiversion, "apiversion", false, wski18n.T("whisk API version"))
    propertyGetCmd.Flags().BoolVar(&flags.property.apibuild, "apibuild", false, wski18n.T("whisk API build version"))
    propertyGetCmd.Flags().BoolVar(&flags.property.apibuildno, "apibuildno", false, wski18n.T("whisk API build number"))
    propertyGetCmd.Flags().BoolVar(&flags.property.cliversion, "cliversion", false, wski18n.T("whisk CLI version"))
    propertyGetCmd.Flags().BoolVar(&flags.property.namespace, "namespace", false, wski18n.T("whisk namespace"))
    propertyGetCmd.Flags().BoolVar(&flags.property.all, "all", false, wski18n.T("all properties"))

    propertySetCmd.Flags().StringVarP(&flags.global.auth, "auth", "u", "", wski18n.T("authorization `KEY`"))
    propertySetCmd.Flags().StringVar(&flags.property.apihostSet, "apihost", "", wski18n.T("whisk API `HOST`"))
    propertySetCmd.Flags().StringVar(&flags.property.insecureSet, "insecure", "", wski18n.T("insecure SSL"))
    propertySetCmd.Flags().StringVar(&flags.property.apiversionSet, "apiversion", "", wski18n.T("whisk API `VERSION`"))
    propertySetCmd.Flags().StringVar(&flags.property.namespaceSet, "namespace", "", wski18n.T("whisk `NAMESPACE`"))

    propertyUnsetCmd.Flags().BoolVar(&flags.property.auth, "auth", false, wski18n.T("authorization key"))
    propertyUnsetCmd.Flags().BoolVar(&flags.property.apihost, "apihost", false, wski18n.T("whisk API host"))
    propertyUnsetCmd.Flags().BoolVar(&flags.property.insecure, "insecure", false, wski18n.T("insecure SSL"))
    propertyUnsetCmd.Flags().BoolVar(&flags.property.apiversion, "apiversion", false, wski18n.T("whisk API version"))
    propertyUnsetCmd.Flags().BoolVar(&flags.property.namespace, "namespace", false, wski18n.T("whisk namespace"))
}

func setDefaultProperties() {
    Properties.Auth = DefaultAuth
    Properties.Namespace = DefaultNamespace
    Properties.APIHost = DefaultAPIHost
    Properties.InsecureSSL = DefaultInsecureSSL
    Properties.APIBuild = DefaultAPIBuild
    Properties.APIBuildNo = DefaultAPIBuildNo
    Properties.APIVersion = DefaultAPIVersion
    Properties.PropsFile = DefaultPropsFile
    // Properties.CLIVersion value is set from main's init()
}

func getPropertiesFilePath() (propsFilePath string, werr error) {
    var envExists bool

    // Environment variable overrides the default properties file path
    if propsFilePath, envExists = os.LookupEnv("WSK_CONFIG_FILE"); envExists == true || propsFilePath != "" {
        whisk.Debug(whisk.DbgInfo, "Using properties file '%s' from WSK_CONFIG_FILE environment variable\n", propsFilePath)
        return propsFilePath, nil
    } else {
        var err error

        propsFilePath, err = homedir.Expand(Properties.PropsFile)

        if err != nil {
            whisk.Debug(whisk.DbgError, "homedir.Expand(%s) failed: %s\n", Properties.PropsFile, err)
            errStr := fmt.Sprintf(
                wski18n.T("Unable to locate properties file '{{.filename}}': {{.err}}",
                    map[string]interface{}{"filename": Properties.PropsFile, "err": err}))
            werr = whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return propsFilePath, werr
        }

        whisk.Debug(whisk.DbgInfo, "Using properties file home dir '%s'\n", propsFilePath)
    }

    return propsFilePath, nil
}

func loadProperties() error {
    var err error

    setDefaultProperties()

    Properties.PropsFile, err = getPropertiesFilePath()
    if err != nil {
        return nil
        //whisk.Debug(whisk.DbgError, "getPropertiesFilePath() failed: %s\n", err)
        //errStr := fmt.Sprintf("Unable to load the properties file: %s", err)
        //werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
        //return werr
    }

    props, err := readProps(Properties.PropsFile)
    if err != nil {
        whisk.Debug(whisk.DbgError, "readProps(%s) failed: %s\n", Properties.PropsFile, err)
        errStr := fmt.Sprintf(
            wski18n.T("Unable to read the properties file '{{.filename}}': {{.err}}",
                map[string]interface{}{"filename": Properties.PropsFile, "err": err}))
        werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
        return werr
    }

    if authToken, hasProp := props["AUTH"]; hasProp {
        Properties.Auth = authToken
    }

    if authToken := os.Getenv("WHISK_AUTH"); len(authToken) > 0 {
        Properties.Auth = authToken
    }

    if apiVersion, hasProp := props["APIVERSION"]; hasProp {
        Properties.APIVersion = apiVersion
    }

    if apiVersion := os.Getenv("WHISK_APIVERSION"); len(apiVersion) > 0 {
        Properties.APIVersion = apiVersion
    }

    if apiHost, hasProp := props["APIHOST"]; hasProp {
        Properties.APIHost = apiHost
    }

    if insecure, hasProp := props["INSECURE_SSL"]; hasProp {
        Properties.InsecureSSL = strings.ToLower(insecure) == "yes" || strings.ToLower(insecure) == "true"
    }

    if apiHost := os.Getenv("WHISK_APIHOST"); len(apiHost) > 0 {
        Properties.APIHost = apiHost
    }

    if namespace, hasProp := props["NAMESPACE"]; hasProp {
        Properties.Namespace = namespace
    }

    if namespace := os.Getenv("WHISK_NAMESPACE"); len(namespace) > 0 {
        Properties.Namespace = namespace
    }

    return nil
}

func parseConfigFlags(cmd *cobra.Command, args []string) error {

    if auth := flags.global.auth; len(auth) > 0 {
        Properties.Auth = auth
        if client != nil {
            client.Config.AuthToken = auth
        }
    }

    if namespace := flags.property.namespaceSet; len(namespace) > 0 {
        Properties.Namespace = namespace
        if client != nil {
            client.Config.Namespace = namespace
        }
    }

    if apiVersion := flags.global.apiversion; len(apiVersion) > 0 {
        Properties.APIVersion = apiVersion
        if client != nil {
            client.Config.Version = apiVersion
        }
    }

    if insecure := flags.global.insecure; insecure {
        Properties.InsecureSSL = insecure
        if client != nil {
            client.Config.Insecure = insecure
        }
    }

    if apiHost := flags.global.apihost; len(apiHost) > 0 {
        Properties.APIHost = apiHost

        if client != nil {
            client.Config.Host = apiHost
            baseURL, err := getURLBase(apiHost)

            if err != nil {
                whisk.Debug(whisk.DbgError, "getURLBase(%s) failed: %s\n", apiHost, err)
                errStr := fmt.Sprintf(
                    wski18n.T("Invalid host address '{{.host}}': {{.err}}",
                        map[string]interface{}{"host": Properties.APIHost, "err": err}))
                werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
                return werr
            }
            client.Config.BaseURL = baseURL
        }
    }

    if flags.global.debug {
        whisk.SetDebug(true)
    }

    if flags.global.verbose {
        whisk.SetVerbose(true)
    }

    return nil
}

func readProps(path string) (map[string]string, error) {

    props := map[string]string{}

    file, err := os.Open(path)
    if err != nil {
        // If file does not exist, just return props
        whisk.Debug(whisk.DbgWarn, "Unable to read whisk properties file '%s' (file open error: %s); falling back to default properties\n" ,path, err)
        return props, nil
    }
    defer file.Close()

    lines := []string{}
    scanner := bufio.NewScanner(file)
    for scanner.Scan() {
        lines = append(lines, scanner.Text())
    }

    props = map[string]string{}
    for _, line := range lines {
        kv := strings.Split(line, "=")
        if len(kv) != 2 {
            // Invalid format; skip
            continue
        }
        props[kv[0]] = kv[1]
    }

    return props, nil

}

func writeProps(path string, props map[string]string) error {

    file, err := os.Create(path)
    if err != nil {
        whisk.Debug(whisk.DbgError, "os.Create(%s) failed: %s\n", path, err)
        errStr := fmt.Sprintf(
            wski18n.T("Whisk properties file write failure: {{.err}}",
                map[string]interface{}{"err": err}))
        werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
        return werr
    }
    defer file.Close()

    writer := bufio.NewWriter(file)
    defer writer.Flush()
    for key, value := range props {
        line := fmt.Sprintf("%s=%s", strings.ToUpper(key), value)
        _, err = fmt.Fprintln(writer, line)
        if err != nil {
            whisk.Debug(whisk.DbgError, "fmt.Fprintln() write to '%s' failed: %s\n", path, err)
            errStr := fmt.Sprintf(
                wski18n.T("Whisk properties file write failure: {{.err}}",
                    map[string]interface{}{"err": err}))
            werr := whisk.MakeWskError(errors.New(errStr), whisk.EXITCODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
            return werr
        }
    }
    return nil
}
